package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Types.SecType;
import com.riskdesk.domain.model.Instrument;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IbGatewayContractResolver {

    private final IbGatewayNativeClient nativeClient;
    private final Map<Instrument, IbGatewayResolvedContract> cache = new ConcurrentHashMap<>();

    public IbGatewayContractResolver(IbGatewayNativeClient nativeClient) {
        this.nativeClient = nativeClient;
    }

    public Optional<IbGatewayResolvedContract> resolve(Instrument instrument) {
        if (!instrument.isExchangeTradedFuture()) {
            return Optional.empty();
        }
        IbGatewayResolvedContract cached = cache.get(instrument);
        if (cached != null) {
            return Optional.of(cached);
        }

        return refresh(instrument);
    }

    public Optional<IbGatewayResolvedContract> refresh(Instrument instrument) {
        if (!instrument.isExchangeTradedFuture()) {
            return Optional.empty();
        }
        cache.remove(instrument);

        List<ContractDetails> details = List.of();
        for (Contract query : buildQueries(instrument)) {
            details = nativeClient.requestContractDetails(query);
            if (!details.isEmpty()) {
                break;
            }
        }

        if (details.isEmpty()) {
            return Optional.empty();
        }

        ContractDetails selected = details.stream()
            .filter(detailsItem -> expiryKey(detailsItem) != null)
            .min(Comparator.comparing(this::expiryKey, Comparator.nullsLast(Comparator.naturalOrder())))
            .orElse(details.get(0));

        IbGatewayResolvedContract resolved = new IbGatewayResolvedContract(instrument, selected.contract(), selected);
        cache.put(instrument, resolved);
        return Optional.of(resolved);
    }

    /**
     * Returns the two nearest contract months (front + next) for an instrument,
     * sorted by expiry date ascending. Used for Open Interest comparison.
     */
    public List<IbGatewayResolvedContract> resolveTopTwo(Instrument instrument) {
        if (!instrument.isExchangeTradedFuture()) {
            return List.of();
        }

        List<ContractDetails> details = List.of();
        for (Contract query : buildQueries(instrument)) {
            details = nativeClient.requestContractDetails(query);
            if (!details.isEmpty()) {
                break;
            }
        }

        if (details.size() < 2) {
            return details.stream()
                .map(d -> new IbGatewayResolvedContract(instrument, d.contract(), d))
                .toList();
        }

        return details.stream()
            .filter(d -> expiryKey(d) != null)
            .sorted(Comparator.comparing(this::expiryKey, Comparator.nullsLast(Comparator.naturalOrder())))
            .limit(2)
            .map(d -> new IbGatewayResolvedContract(instrument, d.contract(), d))
            .toList();
    }

    public void clearCache() {
        cache.clear();
    }

    public Optional<Instrument> detectInstrument(Contract contract) {
        String symbol = safe(contract.symbol());
        String localSymbol = safe(contract.localSymbol());
        String tradingClass = safe(contract.tradingClass());

        if (symbol.equals("MCL") || localSymbol.startsWith("MCL") || tradingClass.equals("MCL")) return Optional.of(Instrument.MCL);
        if (symbol.equals("MGC") || localSymbol.startsWith("MGC") || tradingClass.equals("MGC")) return Optional.of(Instrument.MGC);
        if (symbol.equals("MNQ") || localSymbol.startsWith("MNQ") || tradingClass.equals("MNQ")) return Optional.of(Instrument.MNQ);
        if (symbol.equals("6E") || localSymbol.startsWith("6E") || tradingClass.equals("6E")) return Optional.of(Instrument.E6);
        return Optional.empty();
    }

    private List<Contract> buildQueries(Instrument instrument) {
        return switch (instrument) {
            case MCL -> List.of(
                buildQuery("MCL", "NYMEX", "USD", "100", "MCL"),
                buildQuery("MCL", "NYMEX", "USD", null, null)
            );
            case MGC -> List.of(
                buildQuery("MGC", "COMEX", "USD", "10", "MGC"),
                buildQuery("MGC", "COMEX", "USD", null, null)
            );
            case MNQ -> List.of(
                buildQuery("MNQ", "GLOBEX", "USD", "2", "MNQ"),
                buildQuery("MNQ", "GLOBEX", "USD", null, "MNQ"),
                buildQuery("MNQ", "CME", "USD", null, "MNQ"),
                buildQuery("MNQ", "CME", "USD", null, null)
            );
            case E6 -> List.of(
                buildQuery("EUR", "GLOBEX", "USD", "125000", "6E"),
                buildQuery("EUR", "GLOBEX", "USD", null, "6E"),
                buildQuery("EUR", "CME", "USD", null, "6E"),
                buildQuery("6E", "GLOBEX", "USD", null, "6E")
            );
            case DXY -> List.of();
        };
    }

    private Contract buildQuery(String symbol,
                                String exchange,
                                String currency,
                                String multiplier,
                                String tradingClass) {
        Contract contract = new Contract();
        contract.secType(SecType.FUT);
        contract.symbol(symbol);
        contract.exchange(exchange);
        contract.currency(currency);
        contract.includeExpired(false);
        if (multiplier != null) {
            contract.multiplier(multiplier);
        }
        if (tradingClass != null) {
            contract.tradingClass(tradingClass);
        }
        return contract;
    }

    private LocalDate expiryKey(ContractDetails details) {
        String raw = details.contract().lastTradeDateOrContractMonth();
        if (raw == null || raw.isBlank()) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() >= 8) {
            return LocalDate.parse(digits.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
        }
        if (digits.length() == 6) {
            return YearMonth.parse(digits, DateTimeFormatter.ofPattern("yyyyMM")).atDay(1);
        }
        return null;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

}
