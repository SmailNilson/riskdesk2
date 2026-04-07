package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.Types.SecType;
import com.riskdesk.domain.contract.port.OpenInterestProvider;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.OptionalLong;

/**
 * IBKR adapter implementing the domain OpenInterestProvider port.
 * Uses one-shot snapshot market data requests to fetch Futures Open Interest.
 */
@Component
public class IbGatewayOpenInterestProvider implements OpenInterestProvider {

    private static final Logger log = LoggerFactory.getLogger(IbGatewayOpenInterestProvider.class);

    private final IbGatewayNativeClient nativeClient;

    public IbGatewayOpenInterestProvider(IbGatewayNativeClient nativeClient) {
        this.nativeClient = nativeClient;
    }

    @Override
    public OptionalLong fetchOpenInterest(Instrument instrument, String contractMonth) {
        if (!instrument.isExchangeTradedFuture()) {
            return OptionalLong.empty();
        }

        Contract contract = buildContract(instrument, contractMonth);
        try {
            return nativeClient.requestSnapshotOpenInterest(contract);
        } catch (Exception e) {
            log.debug("Failed to fetch OI for {} {} — {}", instrument, contractMonth, e.getMessage());
            return OptionalLong.empty();
        }
    }

    /**
     * Fetches the current trading volume for a specific contract month.
     * Used as fallback when OI is unavailable for contract selection.
     */
    @Override
    public OptionalLong fetchVolume(Instrument instrument, String contractMonth) {
        if (!instrument.isExchangeTradedFuture()) {
            return OptionalLong.empty();
        }

        Contract contract = buildContract(instrument, contractMonth);
        try {
            return nativeClient.requestSnapshotVolume(contract);
        } catch (Exception e) {
            log.debug("Failed to fetch volume for {} {} — {}", instrument, contractMonth, e.getMessage());
            return OptionalLong.empty();
        }
    }

    private Contract buildContract(Instrument instrument, String contractMonth) {
        Contract contract = new Contract();
        contract.secType(SecType.FUT);
        contract.currency("USD");
        contract.lastTradeDateOrContractMonth(contractMonth);
        contract.includeExpired(false);

        switch (instrument) {
            case MCL -> {
                contract.symbol("MCL");
                contract.exchange("NYMEX");
                contract.multiplier("100");
                contract.tradingClass("MCL");
            }
            case MGC -> {
                contract.symbol("MGC");
                contract.exchange("COMEX");
                contract.multiplier("10");
                contract.tradingClass("MGC");
            }
            case MNQ -> {
                contract.symbol("MNQ");
                contract.exchange("GLOBEX");
                contract.multiplier("2");
                contract.tradingClass("MNQ");
            }
            case E6 -> {
                contract.symbol("EUR");
                contract.exchange("GLOBEX");
                contract.multiplier("125000");
                contract.tradingClass("6E");
            }
            default -> throw new IllegalArgumentException("Unsupported instrument for OI: " + instrument);
        }

        return contract;
    }
}
