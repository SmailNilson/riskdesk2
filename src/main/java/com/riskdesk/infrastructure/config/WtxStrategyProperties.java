package com.riskdesk.infrastructure.config;

import com.riskdesk.domain.engine.strategy.wtx.WtxConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

@ConfigurationProperties(prefix = "riskdesk.wtx")
public class WtxStrategyProperties {

    private boolean enabled = false;
    private List<String> instruments = List.of("MNQ", "MCL", "MGC");
    private String timeframe = "5m";
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

    public WtxConfig toConfig() {
        return new WtxConfig(
                instruments, timeframe,
                n1, n2, signalPeriod, nsc, nsv,
                useCompra, useCompra1, useVenta, useVenta1,
                reverseOnOpp, fixedQty,
                maxDailyLossUsd,
                forceCloseNy, nySessionEndHour, nySessionEndMin, closeBeforeMin
        );
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getInstruments() { return instruments; }
    public void setInstruments(List<String> instruments) { this.instruments = instruments; }

    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }

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
}
