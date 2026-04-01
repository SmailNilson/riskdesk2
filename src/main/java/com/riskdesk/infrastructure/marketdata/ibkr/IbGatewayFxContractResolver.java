package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.Types.SecType;
import com.riskdesk.domain.marketdata.model.FxPair;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

@Service
public class IbGatewayFxContractResolver {

    private record FxContractSpec(String symbol, String currency, String exchange) {}

    private static final Map<FxPair, FxContractSpec> IBKR_MAPPINGS = Map.of(
        FxPair.EURUSD, new FxContractSpec("EUR", "USD", "IDEALPRO"),
        FxPair.USDJPY, new FxContractSpec("USD", "JPY", "IDEALPRO"),
        FxPair.GBPUSD, new FxContractSpec("GBP", "USD", "IDEALPRO"),
        FxPair.USDCAD, new FxContractSpec("USD", "CAD", "IDEALPRO"),
        FxPair.USDSEK, new FxContractSpec("USD", "SEK", "IDEALPRO"),
        FxPair.USDCHF, new FxContractSpec("USD", "CHF", "IDEALPRO")
    );

    private final Map<FxPair, Contract> contracts = new EnumMap<>(FxPair.class);

    public Optional<Contract> resolve(FxPair pair) {
        return Optional.ofNullable(contracts.computeIfAbsent(pair, this::buildContract));
    }

    private Contract buildContract(FxPair pair) {
        FxContractSpec spec = IBKR_MAPPINGS.get(pair);
        if (spec == null) {
            return null;
        }
        Contract contract = new Contract();
        contract.secType(SecType.CASH);
        contract.symbol(spec.symbol());
        contract.currency(spec.currency());
        contract.exchange(spec.exchange());
        return contract;
    }
}
