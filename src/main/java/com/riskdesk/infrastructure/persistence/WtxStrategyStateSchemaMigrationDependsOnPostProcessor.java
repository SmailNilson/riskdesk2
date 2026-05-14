package com.riskdesk.infrastructure.persistence;

import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Forces the JPA {@code EntityManagerFactory} — and therefore Hibernate's
 * {@code ddl-auto} schema update — to wait for {@link WtxStrategyStateSchemaMigration}.
 *
 * Without this ordering Hibernate would try (and fail) to alter the
 * {@code wtx_strategy_states} primary key before the legacy table is dropped.
 */
@Component
public class WtxStrategyStateSchemaMigrationDependsOnPostProcessor
        extends EntityManagerFactoryDependsOnPostProcessor {

    public WtxStrategyStateSchemaMigrationDependsOnPostProcessor() {
        super(WtxStrategyStateSchemaMigration.class);
    }
}
