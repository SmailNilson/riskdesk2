package com.riskdesk.domain.marketdata.model;

public enum FxPair {

    EURUSD("EUR", "USD"),
    USDJPY("USD", "JPY"),
    GBPUSD("GBP", "USD"),
    USDCAD("USD", "CAD"),
    USDSEK("USD", "SEK"),
    USDCHF("USD", "CHF");

    private final String baseCurrency;
    private final String quoteCurrency;

    FxPair(String baseCurrency, String quoteCurrency) {
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
    }

    public String baseCurrency() {
        return baseCurrency;
    }

    public String quoteCurrency() {
        return quoteCurrency;
    }

    public String displayName() {
        return baseCurrency + "." + quoteCurrency;
    }
}
