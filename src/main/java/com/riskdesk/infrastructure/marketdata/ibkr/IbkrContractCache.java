package com.riskdesk.infrastructure.marketdata.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.util.EnumMap;
import java.util.Map;

/**
 * Discovers and caches IBKR contract IDs (conids) for each instrument.
 *
 * Default conids are hard-coded as a safety fallback; they are overridden
 * on startup by a live lookup against the IBKR gateway (if reachable).
 *
 * Update defaults after each contract roll if the gateway is unavailable.
 */
public class IbkrContractCache {

    private static final Logger log = LoggerFactory.getLogger(IbkrContractCache.class);

    // Front-month conids — update after contract rolls
    private static final Map<Instrument, Long> DEFAULTS = Map.of(
        Instrument.MCL, 661016514L,  // Micro WTI Crude Oil  MAY26
        Instrument.MGC, 706903676L,  // Micro Gold           APR26
        Instrument.E6,  496647057L,  // EUR/USD Futures (6E) JUN26
        Instrument.MNQ, 770561201L   // Micro E-mini Nasdaq  JUN26
    );

    // Base conids for contract search (stable across rolls)
    private static final Map<Instrument, Long> BASE_CONIDS = Map.of(
        Instrument.MCL, 500567051L,
        Instrument.MGC, 79702479L,
        Instrument.E6,  39453434L,
        Instrument.MNQ, 362687422L
    );

    // Exchange mappings
    private static final Map<Instrument, String> EXCHANGES = Map.of(
        Instrument.MCL, "NYMEX",
        Instrument.MGC, "COMEX",
        Instrument.E6,  "CME",
        Instrument.MNQ, "CME"
    );

    private final Map<Instrument, Long> conids    = new EnumMap<>(Instrument.class);
    private final RestTemplate          restTemplate;
    private final ObjectMapper          objectMapper;
    private final String                baseUrl;

    public IbkrContractCache(RestTemplate restTemplate, ObjectMapper objectMapper, String baseUrl) {
        this.restTemplate  = restTemplate;
        this.objectMapper  = objectMapper;
        this.baseUrl       = baseUrl;
        conids.putAll(DEFAULTS);
    }

    public long getConid(Instrument instrument) {
        return conids.getOrDefault(instrument, DEFAULTS.getOrDefault(instrument, 0L));
    }

    /**
     * Attempts a live conid lookup via secdef/info to resolve front-month contracts.
     * Falls back to defaults silently on failure.
     * Called once during application startup.
     */
    public void refreshConids() {
        for (Instrument inst : Instrument.exchangeTradedFutures()) {
            refreshInstrument(inst);
        }
    }

    private void refreshInstrument(Instrument inst) {
        try {
            // Step 1: search for available months
            String searchJson = restTemplate.postForObject(
                baseUrl + "/iserver/secdef/search",
                Map.of("symbol", searchSymbol(inst), "secType", "FUT"),
                String.class);
            if (searchJson == null) return;

            JsonNode searchResults = objectMapper.readTree(searchJson);
            if (!searchResults.isArray() || searchResults.isEmpty()) return;

            // Find the FUT section to get available months
            JsonNode sections = searchResults.get(0).path("sections");
            String frontMonth = null;
            for (JsonNode sec : sections) {
                if ("FUT".equals(sec.path("secType").asText())) {
                    String months = sec.path("months").asText("");
                    if (!months.isEmpty()) {
                        frontMonth = months.split(";")[0]; // First = front month
                    }
                    break;
                }
            }
            if (frontMonth == null) return;

            // Step 2: resolve specific conid for this month
            long baseConid = BASE_CONIDS.getOrDefault(inst, searchResults.get(0).path("conid").asLong(0));
            String exchange = EXCHANGES.getOrDefault(inst, "");
            String url = String.format("%s/iserver/secdef/info?conid=%d&secType=FUT&month=%s&exchange=%s",
                baseUrl, baseConid, frontMonth, exchange);
            String infoJson = restTemplate.getForObject(url, String.class);
            if (infoJson == null) return;

            JsonNode infoResults = objectMapper.readTree(infoJson);
            if (infoResults.isArray() && !infoResults.isEmpty()) {
                long conid = infoResults.get(0).path("conid").asLong(0);
                if (conid > 0) {
                    conids.put(inst, conid);
                    log.info("IBKR conid resolved: {} → {} ({})", inst, conid, frontMonth);
                }
            }
        } catch (Exception e) {
            log.debug("IBKR {} conid refresh failed (using default): {}", inst, e.getMessage());
        }
    }

    private String searchSymbol(Instrument instrument) {
        if (instrument == Instrument.E6) {
            return "EUR";
        }
        return instrument.name();
    }
}
