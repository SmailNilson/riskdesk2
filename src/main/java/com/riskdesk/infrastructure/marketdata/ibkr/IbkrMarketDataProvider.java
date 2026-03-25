package com.riskdesk.infrastructure.marketdata.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.marketdata.port.MarketDataProvider;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Live price provider using IBKR Client Portal Gateway market-data snapshot.
 *
 * IBKR snapshot field codes:
 *   31 = Last Price   84 = Bid   86 = Ask
 *
 * Note: first snapshot call subscribes; prices may be empty on first tick.
 */
public class IbkrMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(IbkrMarketDataProvider.class);
    private static final String FIELDS = "31,84,86";

    private final RestTemplate      restTemplate;
    private final ObjectMapper      objectMapper;
    private final IbkrContractCache contractCache;
    private final String            baseUrl;

    public IbkrMarketDataProvider(IbkrRestClient client, ObjectMapper objectMapper,
                                   IbkrContractCache contractCache) {
        this.restTemplate  = client.restTemplate();
        this.objectMapper  = objectMapper;
        this.contractCache = contractCache;
        this.baseUrl       = client.baseUrl();
    }

    @Override
    public Map<Instrument, BigDecimal> fetchPrices() {
        Map<Instrument, BigDecimal> prices = new EnumMap<>(Instrument.class);

        String conidParam = Arrays.stream(Instrument.values())
            .map(i -> String.valueOf(contractCache.getConid(i)))
            .collect(Collectors.joining(","));

        String url = baseUrl + "/iserver/marketdata/snapshot?conids=" + conidParam + "&fields=" + FIELDS;

        try {
            String json = restTemplate.getForObject(url, String.class);
            if (json == null) return prices;

            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) return prices;

            for (JsonNode item : root) {
                long conid = item.path("conid").asLong(0);
                // Field 31 holds last price; IBKR sometimes prefixes with "C " or "H "
                JsonNode lastNode = item.path("31");
                if (lastNode.isMissingNode() || lastNode.isNull()) continue;

                String rawText = lastNode.asText("").replaceAll("[^0-9.]", "");
                if (rawText.isEmpty()) continue;

                double price = Double.parseDouble(rawText);
                if (price <= 0) continue;

                for (Instrument inst : Instrument.values()) {
                    if (contractCache.getConid(inst) == conid) {
                        prices.put(inst, BigDecimal.valueOf(price));
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("IBKR snapshot fetch failed: {}", e.getMessage());
        }

        return prices;
    }

    @Override
    public Optional<BigDecimal> fetchPrice(Instrument instrument) {
        return Optional.ofNullable(fetchPrices().get(instrument));
    }
}
