package com.riskdesk.domain.engine.strategy.wtx;

public enum WtxSignalType {
    /** crossover(wt1, wt2) AND wt1 <= nsv — confirmed reversal from oversold */
    COMPRA,
    /** crossover(wt1, wt2) without oversold filter */
    COMPRA_1,
    /** crossunder(wt1, wt2) AND wt1 >= nsc — confirmed reversal from overbought */
    VENTA,
    /** crossunder(wt1, wt2) without overbought filter */
    VENTA_1
}
