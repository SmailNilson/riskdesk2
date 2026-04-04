package com.riskdesk.domain.contract;

import com.riskdesk.domain.contract.OpenInterestRolloverRule.OpenInterestSnapshot;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenInterestRolloverRuleTest {

    @Test
    void nextOiGreaterThanCurrent_recommendsRoll() {
        OpenInterestSnapshot current = new OpenInterestSnapshot("202505", 50_000);
        OpenInterestSnapshot next    = new OpenInterestSnapshot("202506", 80_000);

        RolloverRecommendation result = OpenInterestRolloverRule.evaluate(Instrument.MCL, current, next);

        assertEquals(RolloverRecommendation.Action.RECOMMEND_ROLL, result.action());
        assertEquals("202505", result.currentMonth());
        assertEquals("202506", result.nextMonth());
        assertEquals(50_000, result.currentOI());
        assertEquals(80_000, result.nextOI());
        assertEquals(Instrument.MCL, result.instrument());
    }

    @Test
    void nextOiLessThanCurrent_holds() {
        OpenInterestSnapshot current = new OpenInterestSnapshot("202505", 80_000);
        OpenInterestSnapshot next    = new OpenInterestSnapshot("202506", 50_000);

        RolloverRecommendation result = OpenInterestRolloverRule.evaluate(Instrument.MCL, current, next);

        assertEquals(RolloverRecommendation.Action.HOLD, result.action());
    }

    @Test
    void equalOi_holds() {
        OpenInterestSnapshot current = new OpenInterestSnapshot("202505", 60_000);
        OpenInterestSnapshot next    = new OpenInterestSnapshot("202506", 60_000);

        RolloverRecommendation result = OpenInterestRolloverRule.evaluate(Instrument.MGC, current, next);

        assertEquals(RolloverRecommendation.Action.HOLD, result.action());
    }

    @Test
    void nextOiZero_holds() {
        OpenInterestSnapshot current = new OpenInterestSnapshot("202505", 50_000);
        OpenInterestSnapshot next    = new OpenInterestSnapshot("202506", 0);

        RolloverRecommendation result = OpenInterestRolloverRule.evaluate(Instrument.MNQ, current, next);

        assertEquals(RolloverRecommendation.Action.HOLD, result.action());
    }

    @Test
    void bothZero_holds() {
        OpenInterestSnapshot current = new OpenInterestSnapshot("202505", 0);
        OpenInterestSnapshot next    = new OpenInterestSnapshot("202506", 0);

        RolloverRecommendation result = OpenInterestRolloverRule.evaluate(Instrument.E6, current, next);

        assertEquals(RolloverRecommendation.Action.HOLD, result.action());
    }

    @Test
    void currentZeroNextPositive_recommendsRoll() {
        OpenInterestSnapshot current = new OpenInterestSnapshot("202505", 0);
        OpenInterestSnapshot next    = new OpenInterestSnapshot("202506", 10_000);

        RolloverRecommendation result = OpenInterestRolloverRule.evaluate(Instrument.MCL, current, next);

        assertEquals(RolloverRecommendation.Action.RECOMMEND_ROLL, result.action());
    }
}
