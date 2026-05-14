package com.riskdesk.infrastructure.persistence;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * One-shot, idempotent schema fixer for {@code wtx_strategy_states}.
 *
 * <p>The table's primary key changed from {@code (instrument)} to the composite
 * {@code (instrument, timeframe)}. Hibernate {@code ddl-auto=update} can neither add a
 * non-null {@code @Id} column to a populated table nor rewrite a primary key, so a
 * legacy table would make startup fail. {@code wtx_strategy_states} is pure runtime
 * state — rebuilt from candles on the next close — so the agreed migration is simply to
 * drop the legacy table and let Hibernate recreate it with the new key.</p>
 *
 * <p>This runs BEFORE the JPA {@code EntityManagerFactory} (wired via
 * {@link WtxStrategyStateSchemaMigrationDependsOnPostProcessor}) and drops the table only
 * when the legacy schema is detected (table present, no {@code timeframe} column). After
 * recreation the guard never triggers again. A fresh DB (no table yet) is a no-op.</p>
 */
@Component
public class WtxStrategyStateSchemaMigration {

    private static final Logger log = LoggerFactory.getLogger(WtxStrategyStateSchemaMigration.class);
    private static final String TABLE = "wtx_strategy_states";

    private final DataSource dataSource;

    public WtxStrategyStateSchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void dropLegacyTableIfPresent() {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection) || hasTimeframeColumn(connection)) {
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + TABLE);
            }
            log.warn("WTX: dropped legacy '{}' table (instrument-only PK) — Hibernate will "
                    + "recreate it with the (instrument, timeframe) composite key. WTX runtime "
                    + "state resets and is rebuilt on the next candle close.", TABLE);
        } catch (SQLException e) {
            // Don't hard-fail startup on the check itself — let Hibernate proceed and surface
            // any real schema problem with its own diagnostics.
            log.warn("WTX: could not verify/migrate '{}' schema — letting Hibernate proceed: {}",
                    TABLE, e.getMessage());
        }
    }

    private boolean tableExists(Connection connection) throws SQLException {
        return countColumns(connection, null) > 0;
    }

    private boolean hasTimeframeColumn(Connection connection) throws SQLException {
        return countColumns(connection, "timeframe") > 0;
    }

    /** Column count for the table (columnName == null) or for one named column — case-insensitive. */
    private int countColumns(Connection connection, String columnName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE LOWER(table_name) = ?"
                + (columnName != null ? " AND LOWER(column_name) = ?" : "");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, TABLE);
            if (columnName != null) {
                ps.setString(2, columnName);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
