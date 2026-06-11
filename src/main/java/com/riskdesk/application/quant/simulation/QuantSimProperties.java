package com.riskdesk.application.quant.simulation;

import com.riskdesk.domain.quant.simulation.QuantSimExitPolicy;
import com.riskdesk.domain.quant.simulation.QuantSimStopMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;

/**
 * Spring-Boot binding for the {@code riskdesk.quant.sim.*} keys — the paper
 * trade-management policy of the Quant 7-Gates simulation harness.
 *
 * <p>Defaults encode the policy validated on the first 863 recorded trades
 * (2026-06-02 → 2026-06-11, intrabar 1m replay, pessimistic both-cross rule):
 * ATR-sized SL/TP (2.0 / 3.0 × ATR14-5m), no flow-AVOID exit, 1h EMA20/50
 * trend alignment required at entry, flat before the CME break. The legacy
 * behaviour is fully restorable via {@code exit-policy=FLOW_AVOID},
 * {@code stop-mode=FIXED}, {@code htf-filter-enabled=false},
 * {@code eod-flat-enabled=false}.</p>
 */
@ConfigurationProperties(prefix = "riskdesk.quant.sim")
public class QuantSimProperties {

    /** Behaviour on a pattern flip to AVOID while a paper trade is open. */
    private QuantSimExitPolicy exitPolicy = QuantSimExitPolicy.SLTP_ONLY;

    /** SL/TP sizing — fixed legacy points or ATR multiples. */
    private QuantSimStopMode stopMode = QuantSimStopMode.ATR;

    /** Timeframe the ATR is computed on (candles table timeframe key). */
    private String atrTimeframe = "5m";

    /** ATR look-back period (Wilder smoothing). */
    private int atrPeriod = 14;

    /** Stop-loss offset in ATR multiples. */
    private double slAtrMult = 2.0;

    /** TP1 offset in ATR multiples. */
    private double tp1AtrMult = 3.0;

    /** TP2 offset in ATR multiples (kept at 2× TP1 to mirror the legacy ratio). */
    private double tp2AtrMult = 6.0;

    /**
     * Require the higher-timeframe trend to agree with the trade direction at
     * entry (fast EMA above slow EMA for LONG, below for SHORT). On the
     * calibration window the unfiltered LONG side ran -0.25R/trade while
     * HTF-aligned entries ran +0.32R/trade.
     */
    private boolean htfFilterEnabled = true;

    /** Higher timeframe used by the trend filter. */
    private String htfTimeframe = "1h";

    /** Fast EMA period of the trend filter. */
    private int htfEmaFast = 20;

    /** Slow EMA period of the trend filter. */
    private int htfEmaSlow = 50;

    /**
     * Flatten every open paper trade ahead of the 17:00 ET CME break and block
     * fresh entries in the run-up — mirrors the Auto-IBKR force-close so the
     * paper row and its live mirror resolve together.
     */
    private boolean eodFlatEnabled = true;

    /** ET wall-clock time from which open paper rows are flattened (until 18:00 ET). */
    private LocalTime eodFlatFrom = LocalTime.of(16, 55);

    /** ET wall-clock time from which new paper entries are blocked (until 18:00 ET). */
    private LocalTime entryBlackoutFrom = LocalTime.of(16, 50);

    /**
     * Legacy policy bundle used by the backward-compatible service
     * constructors (pre-policy unit tests): immediate flow-AVOID exit, fixed
     * 25/40/80 offsets, no HTF filter, no EOD flat.
     */
    public static QuantSimProperties legacyDefaults() {
        QuantSimProperties p = new QuantSimProperties();
        p.setExitPolicy(QuantSimExitPolicy.FLOW_AVOID);
        p.setStopMode(QuantSimStopMode.FIXED);
        p.setHtfFilterEnabled(false);
        p.setEodFlatEnabled(false);
        return p;
    }

    public QuantSimExitPolicy getExitPolicy() { return exitPolicy; }
    public void setExitPolicy(QuantSimExitPolicy exitPolicy) { this.exitPolicy = exitPolicy; }

    public QuantSimStopMode getStopMode() { return stopMode; }
    public void setStopMode(QuantSimStopMode stopMode) { this.stopMode = stopMode; }

    public String getAtrTimeframe() { return atrTimeframe; }
    public void setAtrTimeframe(String atrTimeframe) { this.atrTimeframe = atrTimeframe; }

    public int getAtrPeriod() { return atrPeriod; }
    public void setAtrPeriod(int atrPeriod) { this.atrPeriod = atrPeriod; }

    public double getSlAtrMult() { return slAtrMult; }
    public void setSlAtrMult(double slAtrMult) { this.slAtrMult = slAtrMult; }

    public double getTp1AtrMult() { return tp1AtrMult; }
    public void setTp1AtrMult(double tp1AtrMult) { this.tp1AtrMult = tp1AtrMult; }

    public double getTp2AtrMult() { return tp2AtrMult; }
    public void setTp2AtrMult(double tp2AtrMult) { this.tp2AtrMult = tp2AtrMult; }

    public boolean isHtfFilterEnabled() { return htfFilterEnabled; }
    public void setHtfFilterEnabled(boolean htfFilterEnabled) { this.htfFilterEnabled = htfFilterEnabled; }

    public String getHtfTimeframe() { return htfTimeframe; }
    public void setHtfTimeframe(String htfTimeframe) { this.htfTimeframe = htfTimeframe; }

    public int getHtfEmaFast() { return htfEmaFast; }
    public void setHtfEmaFast(int htfEmaFast) { this.htfEmaFast = htfEmaFast; }

    public int getHtfEmaSlow() { return htfEmaSlow; }
    public void setHtfEmaSlow(int htfEmaSlow) { this.htfEmaSlow = htfEmaSlow; }

    public boolean isEodFlatEnabled() { return eodFlatEnabled; }
    public void setEodFlatEnabled(boolean eodFlatEnabled) { this.eodFlatEnabled = eodFlatEnabled; }

    public LocalTime getEodFlatFrom() { return eodFlatFrom; }
    public void setEodFlatFrom(LocalTime eodFlatFrom) { this.eodFlatFrom = eodFlatFrom; }

    public LocalTime getEntryBlackoutFrom() { return entryBlackoutFrom; }
    public void setEntryBlackoutFrom(LocalTime entryBlackoutFrom) { this.entryBlackoutFrom = entryBlackoutFrom; }
}
