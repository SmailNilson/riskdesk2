package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.controller.Position;

import java.util.List;
import java.util.Map;

public record IbGatewayAccountSnapshot(
    String accountId,
    List<String> accounts,
    Map<String, String> values,
    List<Position> positions
) {}
