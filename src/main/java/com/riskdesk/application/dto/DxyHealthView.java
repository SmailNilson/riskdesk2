package com.riskdesk.application.dto;

import java.time.Instant;
import java.util.List;

public record DxyHealthView(
    String status,
    Instant latestTimestamp,
    String source,
    long maxSkewSeconds,
    List<DxyHealthComponentView> components
) {
}
