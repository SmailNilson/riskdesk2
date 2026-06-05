package com.riskdesk.infrastructure.config;

import com.riskdesk.domain.engine.strategy.wtx.WtxConfig;
import com.riskdesk.domain.engine.strategy.wtx.WtxTrailingMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

@ConfigurationProperties(prefix = "riskdesk.wtx")
public class WtxStrategyProperties {

    private boolean enabled = false;
    private List<String> instruments = List.of("MNQ", "MCL", "MGC");
    private List<String> timeframes = List.of("5m", "10m");
    private int n1 = 10;
    private int n2 = 21;
    private int signalPeriod = 4;
    private BigDecimal nsc = BigDecimal.valueOf(53);
    private BigDecimal nsv = BigDecimal.valueOf(-53);
    private boolean useCompra = true;
    private boolean useCompra1 = false;
    private boolean useVenta = true;
    private boolean useVenta1 = false;
    private boolean reverseOnOpp = true;
    private BigDecimal fixedQty = BigDecimal.valueOf(2.0);
    private BigDecimal maxDailyLossUsd = BigDecimal.valueOf(500.0);
    private boolean forceCloseNy = true;
    private int nySessionEndHour = 16;
    private int nySessionEndMin = 40;
    private int closeBeforeMin = 12;
    private BigDecimal initialEquity = BigDecimal.valueOf(10000);

    // ATR trailing exits — Pine "Session + ATR Risk" profile
    private int atrLength = 14;
    // slAtrMult 1.3 ≈ ATR×1.3 (~30pt at MNQ 5m) — backtest-tuned dynamic stop (was 1.4).
    private BigDecimal slAtrMult = BigDecimal.valueOf(1.3);
    private BigDecimal tpAtrMult = BigDecimal.valueOf(2.1);
    private BigDecimal trailingAtrMult = BigDecimal.valueOf(2.0);
    private BigDecimal trailingActivationR = BigDecimal.valueOf(0.5);

    // Point-based trailing exits (default). arm +30 / trail 15 beat ATR-scaled arm/trail in
    // backtests because ATR-scaling widens the trail on big momentum legs (surrendering profit).
    // slPoints 0 → dynamic slAtrMult*ATR stop; set >0 for a fixed point stop (e.g. 30).
    private WtxTrailingMode trailingMode = WtxTrailingMode.POINTS;
    private BigDecimal trailingActivationPoints = BigDecimal.valueOf(30);
    private BigDecimal trailingPoints = BigDecimal.valueOf(15);
    private BigDecimal slPoints = BigDecimal.ZERO;
    // 30/15 are MNQ-scale; MCL/MGC/E6 have very different point scales (a +30 move ~never arms), so they
    // stay on instrument-relative ATR trailing. Empty list = apply POINTS to every instrument.
    private List<String> trailingPointsInstruments = List.of("MNQ");

    // Clear the daily max-loss latch + rebaseline equity at the 17:00 ET CME day boundary,
    // even if no fresh candle has been processed yet (WtxDailyResetScheduler).
    private boolean dailyResetEnabled = true;

    // HTF bias — Pine "HTF" profile
    private String htfTimeframe = "1h";
    private int htfFastLen = 21;
    private int htfSlowLen = 55;

    // Structure proxy — Pine "Strict" profile
    private int structureLookback = 12;
    private BigDecimal sweepBufferAtr = BigDecimal.valueOf(0.05);

    // IBKR auto-execution — used by WtxExecutionBridge when state.autoExecutionEnabled is true
    private String brokerAccountId = "wtx-default";

    /**
     * Pre-flight margin check policy. PORTFOLIO_HEURISTIC (default) uses the existing
     * portfolio snapshot cache (no extra IBKR call, zero latency). OFF disables the check
     * (legacy behavior — IBKR rejects with code 201 if margin is insufficient). WHATIF
     * sends an {@code Order.whatIf=true} to IBKR for an exact ground-truth check but adds
     * 200-500ms of latency per signal — opt-in only.
     */
    public enum PreflightMode { OFF, PORTFOLIO_HEURISTIC, WHATIF }
    // OFF by default: the PORTFOLIO_HEURISTIC (15% of notional) over-estimated futures
    // margin by ~3.5x for micro index futures (e.g. MNQ: est. 9099 vs IBKR real ~2600),
    // producing false NO MARGIN denials on reverses that IBKR would have accepted. IBKR's
    // own margin check is authoritative and exact, so we let it decide and surface a real
    // code-201 reject if it ever happens. Re-enable (PORTFOLIO_HEURISTIC / WHATIF) only with
    // a calibrated, per-instrument estimate.
    private PreflightMode preflightMode = PreflightMode.OFF;

    /**
     * Conservative margin buffer applied by the PORTFOLIO_HEURISTIC pre-flight: the
     * estimated initial margin for the order is computed as
     * {@code price × contractMultiplier × qty × preflightMarginPercent}. Default 0.15
     * (15%) — IBKR futures init margin is typically 10-12% for the micros, the extra
     * buffer absorbs intraday changes and overnight margin bumps.
     */
    private BigDecimal preflightMarginPercent = new BigDecimal("0.15");

    public WtxConfig toConfig() {
        return new WtxConfig(
                instruments, timeframes,
                n1, n2, signalPeriod, nsc, nsv,
                useCompra, useCompra1, useVenta, useVenta1,
                reverseOnOpp, fixedQty,
                maxDailyLossUsd,
                forceCloseNy, nySessionEndHour, nySessionEndMin, closeBeforeMin,
                atrLength, slAtrMult, tpAtrMult, trailingAtrMult, trailingActivationR,
                htfTimeframe, htfFastLen, htfSlowLen,
                structureLookback, sweepBufferAtr,
                trailingMode, trailingActivationPoints, trailingPoints, slPoints,
                dailyResetEnabled, trailingPointsInstruments
        );
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getInstruments() { return instruments; }
    public void setInstruments(List<String> instruments) { this.instruments = instruments; }

    public List<String> getTimeframes() { return timeframes; }
    public void setTimeframes(List<String> timeframes) { this.timeframes = timeframes; }

    public int getN1() { return n1; }
    public void setN1(int n1) { this.n1 = n1; }

    public int getN2() { return n2; }
    public void setN2(int n2) { this.n2 = n2; }

    public int getSignalPeriod() { return signalPeriod; }
    public void setSignalPeriod(int signalPeriod) { this.signalPeriod = signalPeriod; }

    public BigDecimal getNsc() { return nsc; }
    public void setNsc(BigDecimal nsc) { this.nsc = nsc; }

    public BigDecimal getNsv() { return nsv; }
    public void setNsv(BigDecimal nsv) { this.nsv = nsv; }

    public boolean isUseCompra() { return useCompra; }
    public void setUseCompra(boolean useCompra) { this.useCompra = useCompra; }

    public boolean isUseCompra1() { return useCompra1; }
    public void setUseCompra1(boolean useCompra1) { this.useCompra1 = useCompra1; }

    public boolean isUseVenta() { return useVenta; }
    public void setUseVenta(boolean useVenta) { this.useVenta = useVenta; }

    public boolean isUseVenta1() { return useVenta1; }
    public void setUseVenta1(boolean useVenta1) { this.useVenta1 = useVenta1; }

    public boolean isReverseOnOpp() { return reverseOnOpp; }
    public void setReverseOnOpp(boolean reverseOnOpp) { this.reverseOnOpp = reverseOnOpp; }

    public BigDecimal getFixedQty() { return fixedQty; }
    public void setFixedQty(BigDecimal fixedQty) { this.fixedQty = fixedQty; }

    public BigDecimal getMaxDailyLossUsd() { return maxDailyLossUsd; }
    public void setMaxDailyLossUsd(BigDecimal maxDailyLossUsd) { this.maxDailyLossUsd = maxDailyLossUsd; }

    public boolean isForceCloseNy() { return forceCloseNy; }
    public void setForceCloseNy(boolean forceCloseNy) { this.forceCloseNy = forceCloseNy; }

    public int getNySessionEndHour() { return nySessionEndHour; }
    public void setNySessionEndHour(int nySessionEndHour) { this.nySessionEndHour = nySessionEndHour; }

    public int getNySessionEndMin() { return nySessionEndMin; }
    public void setNySessionEndMin(int nySessionEndMin) { this.nySessionEndMin = nySessionEndMin; }

    public int getCloseBeforeMin() { return closeBeforeMin; }
    public void setCloseBeforeMin(int closeBeforeMin) { this.closeBeforeMin = closeBeforeMin; }

    public BigDecimal getInitialEquity() { return initialEquity; }
    public void setInitialEquity(BigDecimal initialEquity) { this.initialEquity = initialEquity; }

    public int getAtrLength() { return atrLength; }
    public void setAtrLength(int atrLength) { this.atrLength = atrLength; }

    public BigDecimal getSlAtrMult() { return slAtrMult; }
    public void setSlAtrMult(BigDecimal slAtrMult) { this.slAtrMult = slAtrMult; }

    public BigDecimal getTpAtrMult() { return tpAtrMult; }
    public void setTpAtrMult(BigDecimal tpAtrMult) { this.tpAtrMult = tpAtrMult; }

    public BigDecimal getTrailingAtrMult() { return trailingAtrMult; }
    public void setTrailingAtrMult(BigDecimal trailingAtrMult) { this.trailingAtrMult = trailingAtrMult; }

    public BigDecimal getTrailingActivationR() { return trailingActivationR; }
    public void setTrailingActivationR(BigDecimal trailingActivationR) { this.trailingActivationR = trailingActivationR; }

    public WtxTrailingMode getTrailingMode() { return trailingMode; }
    public void setTrailingMode(WtxTrailingMode trailingMode) { this.trailingMode = trailingMode; }

    public BigDecimal getTrailingActivationPoints() { return trailingActivationPoints; }
    public void setTrailingActivationPoints(BigDecimal trailingActivationPoints) { this.trailingActivationPoints = trailingActivationPoints; }

    public BigDecimal getTrailingPoints() { return trailingPoints; }
    public void setTrailingPoints(BigDecimal trailingPoints) { this.trailingPoints = trailingPoints; }

    public BigDecimal getSlPoints() { return slPoints; }
    public void setSlPoints(BigDecimal slPoints) { this.slPoints = slPoints; }

    public List<String> getTrailingPointsInstruments() { return trailingPointsInstruments; }
    public void setTrailingPointsInstruments(List<String> trailingPointsInstruments) { this.trailingPointsInstruments = trailingPointsInstruments; }

    public boolean isDailyResetEnabled() { return dailyResetEnabled; }
    public void setDailyResetEnabled(boolean dailyResetEnabled) { this.dailyResetEnabled = dailyResetEnabled; }

    public String getHtfTimeframe() { return htfTimeframe; }
    public void setHtfTimeframe(String htfTimeframe) { this.htfTimeframe = htfTimeframe; }

    public int getHtfFastLen() { return htfFastLen; }
    public void setHtfFastLen(int htfFastLen) { this.htfFastLen = htfFastLen; }

    public int getHtfSlowLen() { return htfSlowLen; }
    public void setHtfSlowLen(int htfSlowLen) { this.htfSlowLen = htfSlowLen; }

    public int getStructureLookback() { return structureLookback; }
    public void setStructureLookback(int structureLookback) { this.structureLookback = structureLookback; }

    public BigDecimal getSweepBufferAtr() { return sweepBufferAtr; }
    public void setSweepBufferAtr(BigDecimal sweepBufferAtr) { this.sweepBufferAtr = sweepBufferAtr; }

    public String getBrokerAccountId() { return brokerAccountId; }
    public void setBrokerAccountId(String brokerAccountId) { this.brokerAccountId = brokerAccountId; }

    public PreflightMode getPreflightMode() { return preflightMode; }
    public void setPreflightMode(PreflightMode preflightMode) { this.preflightMode = preflightMode; }

    public BigDecimal getPreflightMarginPercent() { return preflightMarginPercent; }
    public void setPreflightMarginPercent(BigDecimal preflightMarginPercent) { this.preflightMarginPercent = preflightMarginPercent; }
}
