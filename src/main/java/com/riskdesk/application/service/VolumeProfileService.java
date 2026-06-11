package com.riskdesk.application.service;

import com.riskdesk.application.dto.VolumeProfileView;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.indicators.SessionVolumeProfileCalculator;
import com.riskdesk.domain.engine.indicators.SessionVolumeProfileCalculator.NakedPoc;
import com.riskdesk.domain.engine.indicators.SessionVolumeProfileCalculator.PriceRange;
import com.riskdesk.domain.engine.indicators.SessionVolumeProfileCalculator.SessionPocRange;
import com.riskdesk.domain.engine.indicators.SessionVolumeProfileCalculator.SessionProfile;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.TradingSessionResolver;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session volume profile (UC-OF-015): builds the current RTH session profile, the
 * prior RTH session, the overnight (Globex) session and the naked-POC ladder for an
 * instrument from internal 1m candles.
 *
 * <p>Sessions follow {@link TradingSessionResolver}: RTH is 09:30–16:00 ET and the
 * overnight session runs from the 18:00 ET Globex reopen of the previous calendar day
 * to the next RTH open — all DST-aware. Profiles are cached per instrument and
 * recomputed at most once per {@code riskdesk.order-flow.volume-profile.cache-seconds}.</p>
 *
 * <p>Data source: PostgreSQL 1m candles only (no external feeds), in line with the
 * project's market-data constraint.</p>
 */
@Service
public class VolumeProfileService {

    private static final String TIMEFRAME_1M = "1m";

    private final CandleRepositoryPort candleRepository;
    private final OrderFlowProperties properties;
    private final Clock clock;
    private final SessionVolumeProfileCalculator calculator = new SessionVolumeProfileCalculator();

    private final ConcurrentHashMap<Instrument, CachedProfile> cache = new ConcurrentHashMap<>();

    @Autowired
    public VolumeProfileService(CandleRepositoryPort candleRepository, OrderFlowProperties properties) {
        this(candleRepository, properties, Clock.systemUTC());
    }

    /** Test constructor with an injectable clock. */
    public VolumeProfileService(CandleRepositoryPort candleRepository,
                                OrderFlowProperties properties, Clock clock) {
        this.candleRepository = candleRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Returns the cached profile for the instrument, recomputing when the cache entry
     * is older than the configured TTL.
     */
    public VolumeProfileView getProfile(Instrument instrument) {
        Instant now = clock.instant();
        long ttlSeconds = Math.max(1, properties.getVolumeProfile().getCacheSeconds());
        CachedProfile cached = cache.get(instrument);
        if (cached != null && cached.computedAt().plusSeconds(ttlSeconds).isAfter(now)) {
            return cached.view();
        }
        synchronized (this) {
            cached = cache.get(instrument);
            if (cached != null && cached.computedAt().plusSeconds(ttlSeconds).isAfter(now)) {
                return cached.view();
            }
            VolumeProfileView view = build(instrument, now);
            cache.put(instrument, new CachedProfile(now, view));
            return view;
        }
    }

    // -------------------------------------------------------------------------

    private VolumeProfileView build(Instrument instrument, Instant now) {
        BigDecimal bucketSize = BigDecimal.valueOf(
            properties.getVolumeProfile().bucketSizeFor(instrument.name()));
        int lookback = Math.max(1, properties.getVolumeProfile().getNakedPocLookbackSessions());

        LocalDate currentDate = TradingSessionResolver.rthSessionDate(now);
        boolean developing = TradingSessionResolver.isWithinRth(now, currentDate);

        // Current session — clamp the window to "now" while developing
        SessionProfile current = profileFor(instrument, bucketSize,
            TradingSessionResolver.rthStart(currentDate),
            min(TradingSessionResolver.rthEnd(currentDate), now));

        // Walk back the prior completed sessions (skip empty/holiday dates)
        List<DatedProfile> priorSessions = new ArrayList<>();
        LocalDate cursor = currentDate;
        int walked = 0;
        while (priorSessions.size() < lookback && walked < lookback * 2 + 10) {
            cursor = TradingSessionResolver.previousRthDate(cursor);
            walked++;
            SessionProfile profile = profileFor(instrument, bucketSize,
                TradingSessionResolver.rthStart(cursor), TradingSessionResolver.rthEnd(cursor));
            if (profile != null) {
                priorSessions.add(new DatedProfile(cursor, profile));
            }
        }

        // Naked POCs: candidates are the completed sessions oldest-first; the current
        // session (completed or developing) acts as the final toucher.
        List<SessionPocRange> candidates = new ArrayList<>();
        for (int i = priorSessions.size() - 1; i >= 0; i--) {
            DatedProfile dp = priorSessions.get(i);
            candidates.add(new SessionPocRange(dp.date(), dp.profile().poc(),
                dp.profile().rangeLow(), dp.profile().rangeHigh()));
        }
        if (current != null && !developing) {
            candidates.add(new SessionPocRange(currentDate, current.poc(),
                current.rangeLow(), current.rangeHigh()));
        }
        PriceRange developingRange = developing && current != null
            ? new PriceRange(current.rangeLow(), current.rangeHigh())
            : null;
        List<NakedPoc> naked = calculator.nakedPocs(candidates, developingRange);

        DatedProfile prior = priorSessions.isEmpty() ? null : priorSessions.get(0);
        OvernightProfile overnight = overnightProfile(instrument, bucketSize, now);

        return new VolumeProfileView(
            instrument.name(),
            toView(currentDate, current, developing),
            prior == null ? null : toView(prior.date(), prior.profile(), false),
            overnight == null ? null : toView(overnight.date(), overnight.profile(), overnight.developing()),
            naked.stream()
                .map(n -> new VolumeProfileView.NakedPocView(n.price().doubleValue(), n.date().toString()))
                .toList()
        );
    }

    /**
     * The overnight (Globex) session anchored on the next-or-current RTH date: from
     * 18:00 ET on the previous calendar day to the RTH open. Developing while "now"
     * is inside the window; on weekends (before the Sunday 18:00 ET reopen) this falls
     * back to the most recent completed overnight session.
     */
    private OvernightProfile overnightProfile(Instrument instrument, BigDecimal bucketSize, Instant now) {
        ZonedDateTime zdt = now.atZone(TradingSessionResolver.CME_ZONE);
        LocalDate anchor = zdt.toLocalDate();
        if (!zdt.toLocalTime().isBefore(TradingSessionResolver.RTH_CLOSE)) {
            anchor = anchor.plusDays(1);
        }
        anchor = rollForwardToWeekday(anchor);

        Instant start = TradingSessionResolver.overnightStart(anchor);
        Instant end = TradingSessionResolver.rthStart(anchor);
        if (now.isBefore(start)) {
            // upcoming overnight has not opened yet (e.g. Saturday) — use the last completed one
            anchor = TradingSessionResolver.rthSessionDate(now);
            start = TradingSessionResolver.overnightStart(anchor);
            end = TradingSessionResolver.rthStart(anchor);
        }
        boolean developing = !now.isBefore(start) && now.isBefore(end);
        SessionProfile profile = profileFor(instrument, bucketSize, start, min(end, now));
        return profile == null ? null : new OvernightProfile(anchor, profile, developing);
    }

    private SessionProfile profileFor(Instrument instrument, BigDecimal bucketSize,
                                      Instant from, Instant to) {
        if (!from.isBefore(to)) return null;
        // findCandlesBetween is inclusive on both ends; the candle stamped exactly at the
        // session end belongs to the next period, so pull the upper bound back 1 second.
        List<Candle> candles = candleRepository.findCandlesBetween(
            instrument, TIMEFRAME_1M, from, to.minusSeconds(1));
        return calculator.compute(candles, bucketSize);
    }

    private VolumeProfileView.SessionProfileView toView(LocalDate date, SessionProfile profile,
                                                        boolean developing) {
        if (profile == null) {
            return new VolumeProfileView.SessionProfileView(date.toString(), null, null, null, 0, developing);
        }
        return new VolumeProfileView.SessionProfileView(
            date.toString(),
            profile.poc() == null ? null : profile.poc().doubleValue(),
            profile.vah() == null ? null : profile.vah().doubleValue(),
            profile.val() == null ? null : profile.val().doubleValue(),
            profile.totalVolume(),
            developing
        );
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static LocalDate rollForwardToWeekday(LocalDate date) {
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date;
    }

    private record CachedProfile(Instant computedAt, VolumeProfileView view) {}

    private record DatedProfile(LocalDate date, SessionProfile profile) {}

    private record OvernightProfile(LocalDate date, SessionProfile profile, boolean developing) {}
}
