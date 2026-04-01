package com.riskdesk.infrastructure.marketdata.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.marketdata.port.HistoricalDataProvider;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Historical OHLCV provider via IBKR Client Portal Gateway HMDS endpoint.
 *
 * Endpoint: GET /hmds/history?conid={}&period={}&bar={}&direction=-1
 *
 * IBKR bar sizes map perfectly to our timeframes:
 *   1m→"1 min", 5m→"5 mins", 10m→"10 mins",
 *   1h→"1 hour", 4h→"4 hours", 1d→"1 day"
 */
public class IbkrHistoricalProvider implements HistoricalDataProvider {

    private static final Logger log = LoggerFactory.getLogger(IbkrHistoricalProvider.class);

    private final RestTemplate      restTemplate;
    private final ObjectMapper      objectMapper;
    private final IbkrContractCache contractCache;
    private final String            baseUrl;

    public IbkrHistoricalProvider(IbkrRestClient client, ObjectMapper objectMapper,
                                   IbkrContractCache contractCache) {
        this.restTemplate  = client.restTemplate();
        this.objectMapper  = objectMapper;
        this.contractCache = contractCache;
        this.baseUrl       = client.baseUrl();
    }

    @Override
    public boolean supports(Instrument instrument, String timeframe) {
        return true;
    }

    @Override
    public List<Candle> fetchHistory(Instrument instrument, String timeframe, int count) {
        long   conid        = contractCache.getConid(instrument);
        if (conid <= 0) {
            return List.of();
        }
        String[] barPeriod  = ibkrConfig(timeframe, count);
        String bar          = barPeriod[0];
        String period       = barPeriod[1];

        String url = baseUrl + "/hmds/history"
            + "?conid="     + conid
            + "&period="    + period
            + "&bar="       + bar.replace(" ", "+")
            + "&direction=-1";

        try {
            String json = restTemplate.getForObject(url, String.class);
            if (json == null) return List.of();

            JsonNode data = objectMapper.readTree(json).path("data");
            if (!data.isArray()) return List.of();

            List<Candle> candles = new ArrayList<>();
            for (JsonNode bar2 : data) {
                long   t = bar2.path("t").asLong();
                double o = bar2.path("o").asDouble(0);
                double h = bar2.path("h").asDouble(0);
                double l = bar2.path("l").asDouble(0);
                double c = bar2.path("c").asDouble(0);
                long   v = bar2.path("v").asLong(0);
                if (o == 0 || c == 0) continue;
                if (o == h && h == l && l == c && v == 0) continue;

                candles.add(new Candle(instrument, timeframe,
                    Instant.ofEpochMilli(t),
                    round(o, instrument), round(h, instrument),
                    round(l, instrument), round(c, instrument), v));
            }

            if (count == Integer.MAX_VALUE) return candles;
            int from = Math.max(0, candles.size() - count);
            return candles.subList(from, candles.size());

        } catch (Exception e) {
            log.warn("IBKR historical fetch failed for {} {}: {}", instrument, timeframe, e.getMessage());
            return List.of();
        }
    }

    private BigDecimal round(double val, Instrument inst) {
        return BigDecimal.valueOf(val).setScale(inst.getTickSize().scale(), RoundingMode.HALF_UP);
    }

    /**
     * Maps our timeframe + count to [ibkrBarSize, ibkrPeriod].
     * Period is calculated to cover at least `count` bars.
     */
    private static String[] ibkrConfig(String timeframe, int count) {
        return switch (timeframe) {
            case "1m"  -> new String[]{"1 min",   Math.max(1, count / 390) + "d"};
            case "5m"  -> new String[]{"5 mins",  Math.max(1, count / 78)  + "d"};
            case "10m" -> new String[]{"10 mins", Math.max(1, count / 39)  + "d"};
            case "1h"  -> new String[]{"1 hour",  count == Integer.MAX_VALUE ? "1y" : Math.max(1, count / 24) + "d"};
            case "4h"  -> new String[]{"4 hours", Math.max(1, count / 6)   + "w"};
            case "1d"  -> new String[]{"1 day",   Math.max(1, count / 5)   + "w"};
            default    -> new String[]{"1 hour",  "1w"};
        };
    }
}
