package com.riskdesk.application.service;

import com.ib.client.Contract;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayContractResolver;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayResolvedContract;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ActiveContractService {

    private static final Map<Integer, Character> MONTH_CODES = Map.ofEntries(
        Map.entry(1, 'F'),
        Map.entry(2, 'G'),
        Map.entry(3, 'H'),
        Map.entry(4, 'J'),
        Map.entry(5, 'K'),
        Map.entry(6, 'M'),
        Map.entry(7, 'N'),
        Map.entry(8, 'Q'),
        Map.entry(9, 'U'),
        Map.entry(10, 'V'),
        Map.entry(11, 'X'),
        Map.entry(12, 'Z')
    );

    private final ObjectProvider<IbGatewayContractResolver> contractResolverProvider;

    public ActiveContractService(ObjectProvider<IbGatewayContractResolver> contractResolverProvider) {
        this.contractResolverProvider = contractResolverProvider;
    }

    public ActiveContractDescriptor describe(Instrument instrument) {
        IbGatewayContractResolver resolver = contractResolverProvider.getIfAvailable();
        if (resolver == null) {
            return fallback(instrument);
        }

        return resolver.resolve(instrument)
            .map(this::toDescriptor)
            .orElseGet(() -> fallback(instrument));
    }

    public String assetLabel(Instrument instrument) {
        return describe(instrument).asset();
    }

    private ActiveContractDescriptor toDescriptor(IbGatewayResolvedContract resolved) {
        Contract contract = resolved.contract();
        String rootInstrument = resolved.instrument().name();
        String contractMonth = resolved.contractMonth();
        String asset = contractMonth == null || contractMonth.isBlank()
            ? rootInstrument
            : rootInstrument + " " + contractMonth;
        String localSymbol = contract == null ? null : blankToNull(contract.localSymbol());
        String contractSymbol = deriveContractSymbol(rootInstrument, contractMonth);
        return new ActiveContractDescriptor(
            rootInstrument,
            asset,
            blankToNull(contractMonth),
            contractSymbol,
            localSymbol,
            blankToNull(resolved.selectionReason())
        );
    }

    private ActiveContractDescriptor fallback(Instrument instrument) {
        return new ActiveContractDescriptor(
            instrument.name(),
            instrument.name(),
            null,
            null,
            null,
            "active contract unavailable"
        );
    }

    private String deriveContractSymbol(String rootInstrument, String contractMonth) {
        if (rootInstrument == null || rootInstrument.isBlank() || contractMonth == null || contractMonth.length() < 6) {
            return null;
        }

        int month;
        try {
            month = Integer.parseInt(contractMonth.substring(4, 6));
        } catch (NumberFormatException ex) {
            return null;
        }
        Character monthCode = MONTH_CODES.get(month);
        if (monthCode == null) {
            return null;
        }

        char yearDigit = contractMonth.charAt(3);
        return rootInstrument + monthCode + yearDigit;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record ActiveContractDescriptor(
        String rootInstrument,
        String asset,
        String contractMonth,
        String contractSymbol,
        String localSymbol,
        String selectionReason
    ) {
    }
}
