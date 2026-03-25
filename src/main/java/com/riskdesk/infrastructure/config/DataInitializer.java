package com.riskdesk.infrastructure.config;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Position;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.trading.port.PositionRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Seeds the H2 in-memory database with realistic test data on startup.
 * Active for default (dev) profile only.
 */
@Component
@Order(1)
@Profile("!prod")
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
    private static final Instant SEED_REFERENCE_TIME = Instant.parse("2026-01-01T00:00:00Z");

    private final CandleRepositoryPort candleRepo;
    private final PositionRepositoryPort positionRepo;

    @Value("${riskdesk.market-data.historical.enabled:false}")
    private boolean historicalEnabled;

    @Value("${riskdesk.ibkr.enabled:false}")
    private boolean ibkrEnabled;

    public DataInitializer(CandleRepositoryPort candleRepo, PositionRepositoryPort positionRepo) {
        this.candleRepo = candleRepo;
        this.positionRepo = positionRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (candleRepo.count() == 0) {
            if (ibkrEnabled) {
                log.info("DataInitializer: IBKR mode enabled, skipping simulated candle seed.");
            } else {
                log.info("DataInitializer: seeding simulated candles (real historical fetch will replace if available)...");
                seedCandles();
            }
        }
        if (positionRepo.count() == 0) {
            seedPositions();
        }
        log.info("DataInitializer: {} candles, {} positions in DB.",
                candleRepo.count(), positionRepo.count());
    }

    // -----------------------------------------------------------------------
    // Candles
    // -----------------------------------------------------------------------

    private void seedCandles() {
        List<Candle> all = new ArrayList<>();

        // MCL — Micro WTI Crude Oil (~$98)
        all.addAll(generateCandles(Instrument.MCL, "5m",  new BigDecimal("98.00"),  new BigDecimal("0.12"), 300));
        all.addAll(generateCandles(Instrument.MCL, "10m", new BigDecimal("98.00"),  new BigDecimal("0.25"), 200));
        all.addAll(generateCandles(Instrument.MCL, "1h",  new BigDecimal("98.00"),  new BigDecimal("0.60"), 200));
        all.addAll(generateCandles(Instrument.MCL, "1d",  new BigDecimal("92.00"),  new BigDecimal("2.20"), 200));

        // MGC — Micro Gold (~$4575)
        all.addAll(generateCandles(Instrument.MGC, "5m",  new BigDecimal("4575.00"), new BigDecimal("3.00"),  300));
        all.addAll(generateCandles(Instrument.MGC, "10m", new BigDecimal("4575.00"), new BigDecimal("6.00"),  200));
        all.addAll(generateCandles(Instrument.MGC, "1h",  new BigDecimal("4575.00"), new BigDecimal("15.00"), 200));
        all.addAll(generateCandles(Instrument.MGC, "1d",  new BigDecimal("3800.00"), new BigDecimal("55.00"), 200));

        // E6 — EUR/USD (~1.1572)
        all.addAll(generateCandles(Instrument.E6, "5m",  new BigDecimal("1.15720"), new BigDecimal("0.00020"), 300));
        all.addAll(generateCandles(Instrument.E6, "10m", new BigDecimal("1.15720"), new BigDecimal("0.00050"), 200));
        all.addAll(generateCandles(Instrument.E6, "1h",  new BigDecimal("1.15720"), new BigDecimal("0.00120"), 200));
        all.addAll(generateCandles(Instrument.E6, "1d",  new BigDecimal("1.08000"), new BigDecimal("0.00500"), 200));

        // MNQ — skip simulated data, use IBKR CME real data only (import via /api/backtest/import-ibkr)

        List<Candle> deduped = deduplicateCandles(all);
        int inserted = saveCandlesSafely(deduped);
        log.info("DataInitializer: inserted {} / {} simulated candles.", inserted, deduped.size());
    }

    /**
     * Generates realistic-looking OHLCV candles with a random walk.
     */
    private List<Candle> generateCandles(Instrument instrument, String timeframe,
                                          BigDecimal startPrice, BigDecimal volatility, int count) {
        List<Candle> candles = new ArrayList<>();
        Random rng = new Random(instrument.ordinal() * 7 + timeframe.length());

        long minutesPerCandle = minutesPerCandle(timeframe);
        Instant start = alignToTimeframe(SEED_REFERENCE_TIME, minutesPerCandle)
                .minus(count * minutesPerCandle, ChronoUnit.MINUTES);

        BigDecimal price = startPrice;

        // Simulate a mild bearish-then-bullish trend to give SMC something to detect
        for (int i = 0; i < count; i++) {
            Instant ts = start.plus(i * minutesPerCandle, ChronoUnit.MINUTES);

            // Random walk with slight bias
            double bias = i < count / 2 ? -0.2 : 0.3; // bearish first half, bullish second
            double move = (rng.nextGaussian() + bias) * volatility.doubleValue() * 0.4;
            BigDecimal change = BigDecimal.valueOf(move).setScale(instrument.getTickSize().scale() + 2, java.math.RoundingMode.HALF_UP);

            BigDecimal open = price;
            BigDecimal close = price.add(change);

            // Wick = 20-60% of the half-range
            double wickFactor = 0.2 + rng.nextDouble() * 0.4;
            BigDecimal halfRange = volatility.multiply(BigDecimal.valueOf(wickFactor));
            BigDecimal high = open.max(close).add(halfRange.multiply(BigDecimal.valueOf(rng.nextDouble())));
            BigDecimal low  = open.min(close).subtract(halfRange.multiply(BigDecimal.valueOf(rng.nextDouble())));

            // Keep prices positive
            if (low.compareTo(BigDecimal.ONE) < 0) {
                low = BigDecimal.ONE;
            }

            long volume = 100L + rng.nextInt(900); // 100–999 ticks

            candles.add(new Candle(instrument, timeframe, ts,
                    round(open, instrument), round(high, instrument),
                    round(low, instrument), round(close, instrument), volume));

            price = close;
        }

        return candles;
    }

    private List<Candle> deduplicateCandles(List<Candle> candles) {
        Map<String, Candle> deduped = new LinkedHashMap<>();
        for (Candle candle : candles) {
            String key = candle.getInstrument() + "|" + candle.getTimeframe() + "|" + candle.getTimestamp();
            deduped.putIfAbsent(key, candle);
        }
        int removed = candles.size() - deduped.size();
        if (removed > 0) {
            log.warn("DataInitializer: removed {} duplicate generated candles before insert.", removed);
        }
        return new ArrayList<>(deduped.values());
    }

    private int saveCandlesSafely(List<Candle> candles) {
        int inserted = 0;
        int skippedDuplicates = 0;

        for (Candle candle : candles) {
            try {
                candleRepo.save(candle);
                inserted++;
            } catch (DataIntegrityViolationException ex) {
                skippedDuplicates++;
                log.debug("DataInitializer: skipped duplicate candle {} {} {}",
                        candle.getInstrument(), candle.getTimeframe(), candle.getTimestamp());
            }
        }

        if (skippedDuplicates > 0) {
            log.warn("DataInitializer: skipped {} duplicate candle inserts.", skippedDuplicates);
        }
        return inserted;
    }

    private long minutesPerCandle(String timeframe) {
        return switch (timeframe) {
            case "1m"  ->   1L;
            case "5m"  ->   5L;
            case "10m" ->  10L;
            case "1h"  ->  60L;
            case "4h"  -> 240L;
            case "1d"  -> 1440L;
            default    ->  10L;
        };
    }

    private Instant alignToTimeframe(Instant timestamp, long minutesPerCandle) {
        long epochMinutes = timestamp.getEpochSecond() / 60;
        long aligned = (epochMinutes / minutesPerCandle) * minutesPerCandle;
        return Instant.ofEpochSecond(aligned * 60);
    }

    /** Round price to the instrument's tick size decimal places. */
    private BigDecimal round(BigDecimal price, Instrument instrument) {
        int scale = instrument.getTickSize().scale();
        return price.setScale(scale, java.math.RoundingMode.HALF_UP);
    }

    // -----------------------------------------------------------------------
    // Positions
    // -----------------------------------------------------------------------

    private void seedPositions() {
        // --- Open positions ---

        // MCL SHORT — bearish CHoCH on 1h, 3 contracts
        Position mclShort = new Position(Instrument.MCL, Side.SHORT, 3, new BigDecimal("99.20"));
        mclShort.setStopLoss(new BigDecimal("100.50"));
        mclShort.setTakeProfit(new BigDecimal("96.80"));
        mclShort.setNotes("Bearish CHoCH on 1h — VWAP rejection");
        mclShort.setCurrentPrice(new BigDecimal("98.23"));
        mclShort.updatePnL(new BigDecimal("98.23"));
        positionRepo.save(mclShort);

        // MGC LONG — bullish OB on 10m, 2 contracts
        Position mgcLong = new Position(Instrument.MGC, Side.LONG, 2, new BigDecimal("4520.00"));
        mgcLong.setStopLoss(new BigDecimal("4490.00"));
        mgcLong.setTakeProfit(new BigDecimal("4620.00"));
        mgcLong.setNotes("Bullish OB + EMA9 cross above EMA50");
        mgcLong.setCurrentPrice(new BigDecimal("4574.90"));
        mgcLong.updatePnL(new BigDecimal("4574.90"));
        positionRepo.save(mgcLong);

        // MNQ LONG — Supertrend bullish, 1 contract
        Position mnqLong = new Position(Instrument.MNQ, Side.LONG, 1, new BigDecimal("23800.00"));
        mnqLong.setStopLoss(new BigDecimal("23500.00"));
        mnqLong.setTakeProfit(new BigDecimal("24500.00"));
        mnqLong.setNotes("Supertrend bullish flip + RSI > 50");
        mnqLong.setCurrentPrice(new BigDecimal("24101.50"));
        mnqLong.updatePnL(new BigDecimal("24101.50"));
        positionRepo.save(mnqLong);

        // --- Closed positions (history) ---

        Position mclWin = new Position(Instrument.MCL, Side.SHORT, 2, new BigDecimal("101.50"));
        mclWin.setStopLoss(new BigDecimal("103.00"));
        mclWin.setTakeProfit(new BigDecimal("98.00"));
        mclWin.setNotes("BOS on 1h — hit TP");
        mclWin.setOpenedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        mclWin.close(new BigDecimal("98.00"));
        positionRepo.save(mclWin);

        Position mgcLoss = new Position(Instrument.MGC, Side.LONG, 1, new BigDecimal("4600.00"));
        mgcLoss.setStopLoss(new BigDecimal("4555.00"));
        mgcLoss.setTakeProfit(new BigDecimal("4680.00"));
        mgcLoss.setNotes("OB entry — stopped out");
        mgcLoss.setOpenedAt(Instant.now().minus(5, ChronoUnit.HOURS));
        mgcLoss.close(new BigDecimal("4555.00"));
        positionRepo.save(mgcLoss);

        Position e6Win = new Position(Instrument.E6, Side.LONG, 1, new BigDecimal("1.15200"));
        e6Win.setStopLoss(new BigDecimal("1.14800"));
        e6Win.setTakeProfit(new BigDecimal("1.16200"));
        e6Win.setNotes("CHoCH bullish on 10m");
        e6Win.setOpenedAt(Instant.now().minus(3, ChronoUnit.HOURS));
        e6Win.close(new BigDecimal("1.16200"));
        positionRepo.save(e6Win);
    }
}
