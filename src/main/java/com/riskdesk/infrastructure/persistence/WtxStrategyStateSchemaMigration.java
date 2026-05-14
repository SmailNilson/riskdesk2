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
import java.util.HashSet;
import java.util.Set;

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
 * {@link WtxStrategyStateSchemaMigrationDependsOnPostProcessor}) and drops the table
 * unless its primary key is <em>already</em> the composite {@code (instrument,
 * timeframe)}. Inspecting the actual PK constraint — not merely the presence of a
 * {@code timeframe} column — also covers the case where a prior {@code ddl-auto} run or
 * hotfix added the column but left the instrument-only PK in place. A fresh DB (no table
 * yet) is a no-op, and once recreated the guard never triggers again.</p>
 */
@Component
public class WtxStrategyStateSchemaMigration {

    private static final Logger log = LoggerFactory.getLogger(WtxStrategyStateSchemaMigration.class);
    private static final String TABLE = "wtx_strategy_states";
    private static final Set<String> EXPECTED_PK = Set.of("instrument", "timeframe");

    private final DataSource dataSource;

    public WtxStrategyStateSchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void dropLegacyTableIfPresent() {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection)) {
                return; // fresh DB — Hibernate creates the table with the composite key
            }
            Set<String> primaryKey = primaryKeyColumns(connection);
            if (primaryKey.equals(EXPECTED_PK)) {
                return; // already on the (instrument, timeframe) composite key
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + TABLE);
            }
            log.warn("WTX: dropped '{}' table (primary key was {}, expected {}) — Hibernate will "
                    + "recreate it with the (instrument, timeframe) composite key. WTX runtime "
                    + "state resets and is rebuilt on the next candle close.",
                    TABLE, primaryKey, EXPECTED_PK);
        } catch (SQLException e) {
            // Don't hard-fail startup on the check itself — let Hibernate proceed and surface
            // any real schema problem with its own diagnostics.
            log.warn("WTX: could not verify/migrate '{}' schema — letting Hibernate proceed: {}",
                    TABLE, e.getMessage());
        }
    }

    private boolean tableExists(Connection connection) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.columns WHERE LOWER(table_name) = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /** Lower-cased column names that make up the table's primary key (empty if it has none). */
    private Set<String> primaryKeyColumns(Connection connection) throws SQLException {
        String sql = "SELECT LOWER(kcu.column_name) AS col "
                + "FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON tc.constraint_name = kcu.constraint_name "
                + "  AND tc.constraint_schema = kcu.constraint_schema "
                + "WHERE tc.constraint_type = 'PRIMARY KEY' "
                + "  AND LOWER(tc.table_name) = ?";
        Set<String> columns = new HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString("col"));
                }
            }
        }
        return columns;
    }
}
