package com.riskdesk.infrastructure.config;

import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiBiasSource;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiConfig;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiTpMode;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiZoneMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Spring binding for the WTX+RSI strategy. The shape mirrors the Python
 * prototype YAML under {@code auto_trader/config/strategy_*.yaml} so the two
 * stay aligned during parameter optimisation.
 */
@ConfigurationProperties(prefix = "riskdesk.wtxrsi")
public class WtxRsiStrategyProperties {

    private boolean enabled = false;
    private List<String> instruments = List.of("MNQ");
    private List<String> timeframes = List.of("5m", "10m");

    // WaveTrend
    private int wtN1 = 10;
    private int wtN2 = 21;
    private int wtSignalPeriod = 4;
    private BigDecimal wtOverbought = BigDecimal.valueOf(53);
    private BigDecimal wtOversold = BigDecimal.valueOf(-53);
    private WtxRsiZoneMode zoneMode = WtxRsiZoneMode.STRICT_ZONE;
    private int zoneLookbackBars = 5;

    // RSI
    private int rsiLength = 14;
    private int rsiSmaLength = 14;
    private int syncLookbackBars = 3; // X — bars *after* WT cross within which RSI must cross

    // Williams fractal SL
    private int fractalLeftRight = 2; // Y
    private int fractalMaxLookback = 20;
    private int swingBufferTicks = 2;

    // Instrument metadata (MNQ defaults)
    private BigDecimal tickSize = new BigDecimal("0.25");
    private BigDecimal tickValueUsd = new BigDecimal("0.50");

    // Sizing — Chaikin confirmation no longer scales this (entry gate only).
    private int baseContracts = 1;

    // TP
    private WtxRsiTpMode tpMode = WtxRsiTpMode.REVERSAL;
    private BigDecimal tpRMultiple = BigDecimal.ZERO;

    // Confirmation
    private int chaikinFast = 3;
    private int chaikinSlow = 10;
    private boolean chaikinEnabled = true;
    // Entry gate: when true, only Chaikin-confirmed signals may OPEN a position.
    // Exits (reversal / SL / TP) keep their current mechanism. Only effective when
    // chaikinEnabled=true (confirmation must actually be computed).
    // NOTE: this field initialiser is the programmatic fallback (false); the
    // shipped application.properties baseline ENABLES it (chaikin-required=true).
    // Reversible — override to false per env / profile to disable the gate.
    private boolean chaikinRequired = false;

    // Bias source for the optional swingBiasFilter toggle.
    // FRACTAL_HH_HL keeps the strategy domain-pure (no IndicatorService dependency).
    // SMC_ENGINE reuses the production SMC structure (BOS/CHoCH-aware, consistent with WTx).
    private WtxRsiBiasSource biasSource = WtxRsiBiasSource.FRACTAL_HH_HL;

    public WtxRsiConfig toConfig() {
        return new WtxRsiConfig(
                wtN1, wtN2, wtSignalPeriod,
                wtOverbought, wtOversold,
                rsiLength, rsiSmaLength,
                syncLookbackBars,
                zoneMode, zoneLookbackBars,
                fractalLeftRight, fractalMaxLookback,
                swingBufferTicks,
                tickSize, tickValueUsd,
                baseContracts,
                tpMode, tpRMultiple,
                chaikinFast, chaikinSlow, chaikinEnabled,
                biasSource,
                chaikinRequired
        );
    }

    // ── getters / setters (boilerplate, required by Spring binding) ──────
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<String> getInstruments() { return instruments; }
    public void setInstruments(List<String> instruments) { this.instruments = instruments; }
    public List<String> getTimeframes() { return timeframes; }
    public void setTimeframes(List<String> timeframes) { this.timeframes = timeframes; }
    public int getWtN1() { return wtN1; }
    public void setWtN1(int wtN1) { this.wtN1 = wtN1; }
    public int getWtN2() { return wtN2; }
    public void setWtN2(int wtN2) { this.wtN2 = wtN2; }
    public int getWtSignalPeriod() { return wtSignalPeriod; }
    public void setWtSignalPeriod(int wtSignalPeriod) { this.wtSignalPeriod = wtSignalPeriod; }
    public BigDecimal getWtOverbought() { return wtOverbought; }
    public void setWtOverbought(BigDecimal wtOverbought) { this.wtOverbought = wtOverbought; }
    public BigDecimal getWtOversold() { return wtOversold; }
    public void setWtOversold(BigDecimal wtOversold) { this.wtOversold = wtOversold; }
    public WtxRsiZoneMode getZoneMode() { return zoneMode; }
    public void setZoneMode(WtxRsiZoneMode zoneMode) { this.zoneMode = zoneMode; }
    public int getZoneLookbackBars() { return zoneLookbackBars; }
    public void setZoneLookbackBars(int zoneLookbackBars) { this.zoneLookbackBars = zoneLookbackBars; }
    public int getRsiLength() { return rsiLength; }
    public void setRsiLength(int rsiLength) { this.rsiLength = rsiLength; }
    public int getRsiSmaLength() { return rsiSmaLength; }
    public void setRsiSmaLength(int rsiSmaLength) { this.rsiSmaLength = rsiSmaLength; }
    public int getSyncLookbackBars() { return syncLookbackBars; }
    public void setSyncLookbackBars(int syncLookbackBars) { this.syncLookbackBars = syncLookbackBars; }
    public int getFractalLeftRight() { return fractalLeftRight; }
    public void setFractalLeftRight(int fractalLeftRight) { this.fractalLeftRight = fractalLeftRight; }
    public int getFractalMaxLookback() { return fractalMaxLookback; }
    public void setFractalMaxLookback(int fractalMaxLookback) { this.fractalMaxLookback = fractalMaxLookback; }
    public int getSwingBufferTicks() { return swingBufferTicks; }
    public void setSwingBufferTicks(int swingBufferTicks) { this.swingBufferTicks = swingBufferTicks; }
    public BigDecimal getTickSize() { return tickSize; }
    public void setTickSize(BigDecimal tickSize) { this.tickSize = tickSize; }
    public BigDecimal getTickValueUsd() { return tickValueUsd; }
    public void setTickValueUsd(BigDecimal tickValueUsd) { this.tickValueUsd = tickValueUsd; }
    public int getBaseContracts() { return baseContracts; }
    public void setBaseContracts(int baseContracts) { this.baseContracts = baseContracts; }
    public WtxRsiTpMode getTpMode() { return tpMode; }
    public void setTpMode(WtxRsiTpMode tpMode) { this.tpMode = tpMode; }
    public BigDecimal getTpRMultiple() { return tpRMultiple; }
    public void setTpRMultiple(BigDecimal tpRMultiple) { this.tpRMultiple = tpRMultiple; }
    public int getChaikinFast() { return chaikinFast; }
    public void setChaikinFast(int chaikinFast) { this.chaikinFast = chaikinFast; }
    public int getChaikinSlow() { return chaikinSlow; }
    public void setChaikinSlow(int chaikinSlow) { this.chaikinSlow = chaikinSlow; }
    public boolean isChaikinEnabled() { return chaikinEnabled; }
    public void setChaikinEnabled(boolean chaikinEnabled) { this.chaikinEnabled = chaikinEnabled; }
    public boolean isChaikinRequired() { return chaikinRequired; }
    public void setChaikinRequired(boolean chaikinRequired) { this.chaikinRequired = chaikinRequired; }
    public WtxRsiBiasSource getBiasSource() { return biasSource; }
    public void setBiasSource(WtxRsiBiasSource biasSource) { this.biasSource = biasSource; }
}
