package com.riskdesk.application.service;

import com.riskdesk.application.dto.MentorIntermarketSnapshot;
import org.springframework.stereotype.Service;

/**
 * Closed-data-source rule:
 * intermarket values must come from the existing IBKR -> PostgreSQL pipeline only.
 *
 * At the moment these correlated instruments are not persisted in the local pipeline,
 * so we deliberately return UNAVAILABLE instead of querying any external source.
 */
@Service
public class MentorIntermarketService {

    public MentorIntermarketSnapshot current() {
        return new MentorIntermarketSnapshot(
            null,
            null,
            null,
            null,
            "UNAVAILABLE"
        );
    }
}
