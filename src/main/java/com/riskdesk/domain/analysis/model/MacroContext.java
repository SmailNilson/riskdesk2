package com.riskdesk.domain.analysis.model;

/** Macro / inter-market context — currently DXY-only. Other fields reserved. */
public record MacroContext(
    Double dxyValue,
    Double dxyChangePercent,
    String dxyDirection            // RISING / FALLING / FLAT
) {
}
