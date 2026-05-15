package com.riskdesk.infrastructure.persistence;

import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Forces the JPA {@code EntityManagerFactory} — and therefore the application's first
 * INSERT into {@code trade_executions} — to wait for {@link TradeExecutionsSchemaMigration},
 * so the legacy {@code NOT NULL} constraint on {@code mentor_signal_review_id} is dropped
 * before any WTX_AUTO / QUANT_AUTO_ARM row is attempted.
 */
@Component
public class TradeExecutionsSchemaMigrationDependsOnPostProcessor
        extends EntityManagerFactoryDependsOnPostProcessor {

    public TradeExecutionsSchemaMigrationDependsOnPostProcessor() {
        super(TradeExecutionsSchemaMigration.class);
    }
}
