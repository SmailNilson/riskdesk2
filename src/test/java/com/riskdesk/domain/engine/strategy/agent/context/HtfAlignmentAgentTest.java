package com.riskdesk.domain.engine.strategy.agent.context;

import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.MacroBias;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.engine.strategy.model.MtfSnapshot;
import com.riskdesk.domain.engine.strategy.model.PdZone;
import com.riskdesk.domain.engine.strategy.model.PriceLocation;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.TriggerContext;
import com.riskdesk.domain.engine.strategy.model.ZoneContext;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HtfAlignmentAgentTest {

    private final HtfAlignmentAgent agent = new HtfAlignmentAgent();
    private final Instant at = Instant.parse("2026-04-17T10:00:00Z");

    @Test
    void three_of_three_aligned_produces_strong_positive_vote_for_bull() {
        MtfSnapshot mtf = new MtfSnapshot(MacroBias.BULL, MacroBias.BULL, MacroBias.BULL);
        AgentVote vote = agent.evaluate(input(MacroBias.BULL, mtf));

        assertThat(vote.abstain()).isFalse();
        assertThat(vote.directionalVote()).isEqualTo(+90);
        assertThat(vote.confidence()).isCloseTo(0.90, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void three_of_three_aligned_produces_strong_negative_vote_for_bear() {
        MtfSnapshot mtf = new MtfSnapshot(MacroBias.BEAR, MacroBias.BEAR, MacroBias.BEAR);
        AgentVote vote = agent.evaluate(input(MacroBias.BEAR, mtf));

        // BEAR reference + 3 BEAR HTFs = aligned → sign is negative by convention
        // (directional votes are on the absolute signed scale; BEAR agents report
        // negative values)
        assertThat(vote.abstain()).isFalse();
        assertThat(vote.directionalVote()).isEqualTo(-90);
    }

    @Test
    void two_of_three_aligned_moderate_vote() {
        MtfSnapshot mtf = new MtfSnapshot(MacroBias.BULL, MacroBias.BULL, MacroBias.NEUTRAL);
        AgentVote vote = agent.evaluate(input(MacroBias.BULL, mtf));

        assertThat(vote.directionalVote()).isEqualTo(+60);
    }

    @Test
    void contradicted_reference_produces_dampened_negative_vote() {
        // Reference BULL but both H4 and Daily are BEAR — net = -2 (2 of 3 opposed).
        // Asymmetric scaling: opposition magnitude is capped (35) so it dampens but
        // does not erase the reference SMC bias.
        MtfSnapshot mtf = new MtfSnapshot(MacroBias.NEUTRAL, MacroBias.BEAR, MacroBias.BEAR);
        AgentVote vote = agent.evaluate(input(MacroBias.BULL, mtf));

        assertThat(vote.directionalVote()).isNegative();
        assertThat(vote.directionalVote()).isEqualTo(-35);
        assertThat(vote.confidence()).isCloseTo(0.55, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void fully_opposed_reference_produces_capped_counter_vote() {
        // Reference BEAR but H1=H4=Daily=BULL → 0/3 aligned, 3/3 opposed (net=-3).
        // The pre-fix system used ±90 here, fully cancelling the SMC reference vote
        // (−70 × 0.85 + 90 × 0.90 ≈ +21 net positive). Capping at ±45 keeps the
        // reference signal dominant (−59.5 + 31.5 ≈ −28 net negative).
        MtfSnapshot mtf = new MtfSnapshot(MacroBias.BULL, MacroBias.BULL, MacroBias.BULL);
        AgentVote vote = agent.evaluate(input(MacroBias.BEAR, mtf));

        // BEAR reference, opposition by all HTFs → counter-vote in the BULL direction
        assertThat(vote.directionalVote()).isEqualTo(+45);
        assertThat(vote.confidence()).isCloseTo(0.70, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void single_opposed_reference_produces_small_counter_vote() {
        // Reference BULL with H1=BEAR, H4=NEUTRAL, D=NEUTRAL → net = -1
        MtfSnapshot mtf = new MtfSnapshot(MacroBias.BEAR, MacroBias.NEUTRAL, MacroBias.NEUTRAL);
        AgentVote vote = agent.evaluate(input(MacroBias.BULL, mtf));

        assertThat(vote.directionalVote()).isEqualTo(-20);
        assertThat(vote.confidence()).isCloseTo(0.45, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void all_neutral_htf_abstains() {
        AgentVote vote = agent.evaluate(input(MacroBias.BULL, MtfSnapshot.neutral()));
        assertThat(vote.abstain()).isTrue();
    }

    @Test
    void neutral_reference_with_dominant_htf_bull_votes_positive() {
        MtfSnapshot mtf = new MtfSnapshot(MacroBias.BULL, MacroBias.BULL, MacroBias.BEAR);
        AgentVote vote = agent.evaluate(input(MacroBias.NEUTRAL, mtf));

        // 2B - 1S = +1 → 15 magnitude
        assertThat(vote.directionalVote()).isEqualTo(+15);
    }

    @Test
    void neutral_reference_with_split_htf_abstains() {
        // 1 BULL + 1 BEAR + 1 NEUTRAL = 1B/1S → split → abstain
        MtfSnapshot mtf = new MtfSnapshot(MacroBias.BULL, MacroBias.BEAR, MacroBias.NEUTRAL);
        AgentVote vote = agent.evaluate(input(MacroBias.NEUTRAL, mtf));

        assertThat(vote.abstain()).isTrue();
    }

    @Test
    void evidence_lists_individual_tf_biases() {
        MtfSnapshot mtf = new MtfSnapshot(MacroBias.BULL, MacroBias.BULL, MacroBias.BULL);
        AgentVote vote = agent.evaluate(input(MacroBias.BULL, mtf));

        assertThat(vote.evidence().toString()).contains("H1=BULL", "H4=BULL", "D=BULL");
    }

    private static StrategyInput input(MacroBias referenceBias, MtfSnapshot mtf) {
        MarketContext ctx = new MarketContext(
            Instrument.MGC, "1h",
            referenceBias, MarketRegime.TRENDING,
            PriceLocation.INSIDE_VA, PdZone.EQUILIBRIUM,
            new BigDecimal("2000"), new BigDecimal("5.0"),
            mtf, Instant.parse("2026-04-17T10:00:00Z")
        );
        return new StrategyInput(ctx, ZoneContext.empty(), TriggerContext.unavailable(), null);
    }
}
