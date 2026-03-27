package com.riskdesk.infrastructure.marketdata.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.model.IbkrWatchlist;
import com.riskdesk.domain.model.IbkrWatchlistInstrument;
import com.riskdesk.domain.trading.port.IbkrWatchlistSourcePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

@Component
public class ClientPortalIbkrWatchlistSource implements IbkrWatchlistSourcePort {

    private static final Logger log = LoggerFactory.getLogger(ClientPortalIbkrWatchlistSource.class);

    private final IbkrProperties properties;
    private final ObjectMapper objectMapper;

    public ClientPortalIbkrWatchlistSource(IbkrProperties properties,
                                           ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<IbkrWatchlist> fetchUserWatchlists() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("IBKR is disabled in the backend configuration.");
        }

        IbkrRestClient client = new IbkrRestClient(properties);
        JsonNode payload = readJson(client, "/iserver/watchlists?SC=USER_WATCHLIST");
        JsonNode lists = payload.path("data").path("user_lists");

        if (!lists.isArray()) {
            throw new IllegalStateException("IBKR Client Portal returned an unexpected watchlists payload.");
        }

        List<IbkrWatchlist> watchlists = new ArrayList<>();
        for (JsonNode listNode : lists) {
            String watchlistId = listNode.path("id").asText(null);
            if (watchlistId == null || watchlistId.isBlank()) {
                continue;
            }
            watchlists.add(readWatchlist(client, listNode, watchlistId));
        }
        return watchlists;
    }

    private IbkrWatchlist readWatchlist(IbkrRestClient client, JsonNode summaryNode, String watchlistId) {
        JsonNode detail = readWatchlistDetail(client, watchlistId);

        IbkrWatchlist watchlist = new IbkrWatchlist();
        watchlist.setWatchlistId(watchlistId);
        watchlist.setName(firstNonBlank(
            detail.path("name").asText(null),
            summaryNode.path("name").asText(null),
            watchlistId
        ));
        watchlist.setReadOnly(detail.path("readOnly").asBoolean(summaryNode.path("read_only").asBoolean(false)));
        watchlist.setImportedAt(Instant.now());
        watchlist.setInstruments(extractInstruments(detail.path("instruments")));
        return watchlist;
    }

    private JsonNode readWatchlistDetail(IbkrRestClient client, String watchlistId) {
        String encodedId = URLEncoder.encode(watchlistId, StandardCharsets.UTF_8);
        JsonNode first = readJson(client, "/iserver/watchlist?id=" + encodedId);
        JsonNode firstInstruments = first.path("instruments");
        if (needsWarmup(firstInstruments)) {
            JsonNode second = readJson(client, "/iserver/watchlist?id=" + encodedId);
            if (!second.path("instruments").isMissingNode()) {
                return second;
            }
        }
        return first;
    }

    private boolean needsWarmup(JsonNode instruments) {
        if (!instruments.isArray() || instruments.isEmpty()) {
            return false;
        }
        Iterator<JsonNode> iterator = instruments.elements();
        while (iterator.hasNext()) {
            JsonNode node = iterator.next();
            if (!node.path("ticker").asText("").isBlank() || !node.path("fullName").asText("").isBlank()) {
                return false;
            }
        }
        return true;
    }

    private List<IbkrWatchlistInstrument> extractInstruments(JsonNode instrumentsNode) {
        List<IbkrWatchlistInstrument> instruments = new ArrayList<>();
        if (!instrumentsNode.isArray()) {
            return instruments;
        }

        int index = 0;
        for (JsonNode node : instrumentsNode) {
            long conid = node.path("conid").asLong(0L);
            if (conid == 0L) {
                conid = node.path("C").asLong(0L);
            }

            IbkrWatchlistInstrument instrument = new IbkrWatchlistInstrument();
            instrument.setPositionIndex(index++);
            instrument.setConid(conid == 0L ? null : conid);
            instrument.setSymbol(blankToNull(node.path("ticker").asText(null)));
            instrument.setLocalSymbol(blankToNull(node.path("fullName").asText(null)));
            instrument.setName(blankToNull(node.path("name").asText(null)));
            instrument.setAssetClass(blankToNull(firstNonBlank(
                node.path("assetClass").asText(null),
                node.path("ST").asText(null)
            )));
            instrument.setInstrumentCode(resolveInstrumentCode(instrument));
            instruments.add(instrument);
        }
        return instruments;
    }

    private String resolveInstrumentCode(IbkrWatchlistInstrument instrument) {
        List<String> candidates = Arrays.asList(
            normalize(instrument.getSymbol()),
            normalize(instrument.getLocalSymbol()),
            normalize(instrument.getName())
        );

        for (String value : candidates) {
            if (value == null) {
                continue;
            }
            if (value.equals("MCL") || value.startsWith("MCL ")) {
                return "MCL";
            }
            if (value.equals("MGC") || value.startsWith("MGC ")) {
                return "MGC";
            }
            if (value.equals("MNQ") || value.startsWith("MNQ ")) {
                return "MNQ";
            }
            if (value.equals("6E") || value.startsWith("6E ") || value.equals("EUR") || value.startsWith("EUR ")) {
                return "E6";
            }
            if (value.equals("DX") || value.startsWith("DX ")) {
                return "DXY";
            }
        }
        return candidates.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.split("\\s+")[0])
            .findFirst()
            .orElse(null);
    }

    private JsonNode readJson(IbkrRestClient client, String path) {
        try {
            String payload = client.restTemplate().getForObject(client.baseUrl() + path, String.class);
            if (payload == null || payload.isBlank()) {
                return objectMapper.nullNode();
            }
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("IBKR watchlist request failed for {}: {}", path, e.getMessage());
            throw new IllegalStateException("IBKR Client Portal watchlists unavailable: " + e.getMessage(), e);
        }
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
