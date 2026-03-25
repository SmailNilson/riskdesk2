package com.riskdesk.domain.trading.vo;

import com.riskdesk.domain.shared.vo.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class PnLResultTest {

    @Test
    void constructor_setsUnrealizedAndRealized() {
        Money unrealized = Money.of("100.00");
        Money realized = Money.of("50.00");
        PnLResult result = new PnLResult(unrealized, realized);
        assertEquals(unrealized, result.unrealized());
        assertEquals(realized, result.realized());
    }

    @Test
    void total_returnsSum() {
        Money unrealized = Money.of("100.00");
        Money realized = Money.of("50.00");
        PnLResult result = new PnLResult(unrealized, realized);
        assertEquals(Money.of("150.00"), result.total());
    }

    @Test
    void total_withNegativeValues() {
        Money unrealized = Money.of(new BigDecimal("-30.00"));
        Money realized = Money.of("100.00");
        PnLResult result = new PnLResult(unrealized, realized);
        assertEquals(Money.of("70.00"), result.total());
    }

    @Test
    void unrealizedFactory_createsWithZeroRealized() {
        Money unrealized = Money.of("200.00");
        PnLResult result = PnLResult.unrealized(unrealized);
        assertEquals(unrealized, result.unrealized());
        assertEquals(Money.ZERO, result.realized());
        assertEquals(unrealized, result.total());
    }

    @Test
    void realizedFactory_createsWithZeroUnrealized() {
        Money realized = Money.of("500.00");
        PnLResult result = PnLResult.realized(realized);
        assertEquals(Money.ZERO, result.unrealized());
        assertEquals(realized, result.realized());
        assertEquals(realized, result.total());
    }

    @Test
    void equals_sameValues_areEqual() {
        PnLResult a = new PnLResult(Money.of("100.00"), Money.of("50.00"));
        PnLResult b = new PnLResult(Money.of("100.00"), Money.of("50.00"));
        assertEquals(a, b);
    }

    @Test
    void equals_differentValues_areNotEqual() {
        PnLResult a = new PnLResult(Money.of("100.00"), Money.of("50.00"));
        PnLResult b = new PnLResult(Money.of("100.00"), Money.of("60.00"));
        assertNotEquals(a, b);
    }
}
