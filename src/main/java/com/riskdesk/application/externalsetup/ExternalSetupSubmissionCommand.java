package com.riskdesk.application.externalsetup;

import com.riskdesk.domain.externalsetup.ExternalSetupConfidence;
import com.riskdesk.domain.externalsetup.ExternalSetupSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;

import java.math.BigDecimal;

public record ExternalSetupSubmissionCommand(
    Instrument instrument,
    Side direction,
    BigDecimal entry,
    BigDecimal stopLoss,
    BigDecimal takeProfit1,
    BigDecimal takeProfit2,
    ExternalSetupConfidence confidence,
    String triggerLabel,
    String payloadJson,
    ExternalSetupSource source,
    String sourceRef
) {
}
