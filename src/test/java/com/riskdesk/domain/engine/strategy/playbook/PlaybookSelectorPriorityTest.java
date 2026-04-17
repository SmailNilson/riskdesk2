package com.riskdesk.domain.engine.strategy.playbook;

import com.riskdesk.domain.engine.strategy.model.MacroBias;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.engine.strategy.model.MtfSnapshot;
import com.riskdesk.domain.engine.strategy.model.PdZone;
import com.riskdesk.domain.engine.strategy.model.PortfolioState;
import com.riskdesk.domain.engine.strategy.model.PriceLocation;
import com.riskdesk.domain.engine.strategy.model.SessionInfo;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the playbook priority order declared in
 * {@code StrategyEngineConfig#playbookSelector}. If this order drifts, trades
 * may be attributed to the wrong setup id and telemetry becomes unreadable.
 */
class PlaybookSelectorPriorityTest {

    private static final Instant AT = Instant.parse("2026-04-17T14:00:00Z");

    /** Mirror of the production order. Any change here MUST match the production config. */
    private static final List<Playbook> PRODUCTION_ORDER = List.of(
        new SilverBulletPlaybook(),
        new NyOpenReversalPlaybook(),
        new LondonSweepPlaybook(),
        new SbdrPlaybook(),
        new LsarPlaybook()
    );

    private final PlaybookSelector selector = new PlaybookSelector(PRODUCTION_ORDER);

    @Test
    void silver_bullet_wins_over_sbdr_when_both_applicable() {
        // NY AM kill zone + BULL bias + DISCOUNT PD → both SB and SBDR match.
        // SB must win because it's more specific.
        MarketContext ctx = new MarketContext(
            Instrument.MNQ, "1h",
            MacroBias.BULL, MarketRegime.TRENDING,
            PriceLocation.INSIDE_VA, PdZone.DISCOUNT,
            new BigDecimal("100"), new BigDecimal("1.0"),
            MtfSnapshot.neutral(), PortfolioState.unknown(),
            new SessionInfo("NY_AM", true, true, false),
            AT
        );
        assertThat(selector.select(ctx))
            .isPresent()
            .get()
            .extracting(Playbook::id)
            .isEqualTo(SilverBulletPlaybook.ID);
    }

    @Test
    void ny_open_reversal_wins_over_lsar_in_ny_kill_zone() {
        // NY AM kill zone + BELOW_VAL + DISCOUNT → both NOR and LSAR match.
        // NOR must win.
        MarketContext ctx = new MarketContext(
            Instrument.MGC, "1h",
            MacroBias.NEUTRAL, MarketRegime.RANGING,
            PriceLocation.BELOW_VAL, PdZone.DISCOUNT,
            new BigDecimal("100"), new BigDecimal("1.0"),
            MtfSnapshot.neutral(), PortfolioState.unknown(),
            new SessionInfo("NY_AM", true, true, false),
            AT
        );
        assertThat(selector.select(ctx))
            .isPresent()
            .get()
            .extracting(Playbook::id)
            .isEqualTo(NyOpenReversalPlaybook.ID);
    }

    @Test
    void london_sweep_wins_over_lsar_in_london_kill_zone() {
        MarketContext ctx = new MarketContext(
            Instrument.E6, "1h",
            MacroBias.NEUTRAL, MarketRegime.RANGING,
            PriceLocation.ABOVE_VAH, PdZone.PREMIUM,
            new BigDecimal("100"), new BigDecimal("1.0"),
            MtfSnapshot.neutral(), PortfolioState.unknown(),
            new SessionInfo("LONDON", true, true, false),
            AT
        );
        assertThat(selector.select(ctx))
            .isPresent()
            .get()
            .extracting(Playbook::id)
            .isEqualTo(LondonSweepPlaybook.ID);
    }

    @Test
    void lsar_selected_when_no_session_specific_match() {
        // Ranging + extreme but unknown session → session-specific playbooks
        // abstain, LSAR takes over
        MarketContext ctx = new MarketContext(
            Instrument.MGC, "1h",
            MacroBias.NEUTRAL, MarketRegime.RANGING,
            PriceLocation.BELOW_VAL, PdZone.DISCOUNT,
            new BigDecimal("100"), new BigDecimal("1.0"),
            MtfSnapshot.neutral(), PortfolioState.unknown(),
            SessionInfo.unknown(),
            AT
        );
        assertThat(selector.select(ctx))
            .isPresent()
            .get()
            .extracting(Playbook::id)
            .isEqualTo(LsarPlaybook.ID);
    }

    @Test
    void sbdr_selected_for_trend_continuation_when_no_session_specific_match() {
        MarketContext ctx = new MarketContext(
            Instrument.MNQ, "1h",
            MacroBias.BULL, MarketRegime.TRENDING,
            PriceLocation.INSIDE_VA, PdZone.DISCOUNT,
            new BigDecimal("100"), new BigDecimal("1.0"),
            MtfSnapshot.neutral(), PortfolioState.unknown(),
            SessionInfo.unknown(),
            AT
        );
        assertThat(selector.select(ctx))
            .isPresent()
            .get()
            .extracting(Playbook::id)
            .isEqualTo(SbdrPlaybook.ID);
    }

    @Test
    void empty_when_nothing_applicable() {
        MarketContext ctx = new MarketContext(
            Instrument.MCL, "10m",
            MacroBias.NEUTRAL, MarketRegime.CHOPPY,
            PriceLocation.INSIDE_VA, PdZone.EQUILIBRIUM,
            new BigDecimal("100"), new BigDecimal("1.0"),
            MtfSnapshot.neutral(), PortfolioState.unknown(),
            SessionInfo.unknown(),
            AT
        );
        assertThat(selector.select(ctx)).isEmpty();
    }
}
