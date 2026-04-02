package com.riskdesk.domain.marketdata.port;

import com.riskdesk.domain.marketdata.model.FxPair;
import com.riskdesk.domain.marketdata.model.FxQuoteSnapshot;

import java.util.Map;
import java.util.Optional;

public interface FxQuoteProvider {

    Map<FxPair, FxQuoteSnapshot> fetchQuotes();

    default Optional<FxQuoteSnapshot> fetchQuote(FxPair pair) {
        return Optional.ofNullable(fetchQuotes().get(pair));
    }
}
