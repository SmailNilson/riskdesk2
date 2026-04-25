package com.riskdesk.domain.analysis.port;

import com.riskdesk.domain.analysis.model.MacroContext;

import java.time.Instant;

/** Read-only port for cross-market context (DXY, intermarket correlations, etc.). */
public interface MacroContextPort {

    record TimedMacro(MacroContext macro, Instant asOf) {}

    TimedMacro contextAsOf(Instant decisionAt);
}
