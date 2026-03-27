package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.riskdesk.domain.model.Instrument;

public record IbGatewayResolvedContract(
    Instrument instrument,
    Contract contract,
    ContractDetails details,
    String contractMonth,
    String selectionReason
) {}
