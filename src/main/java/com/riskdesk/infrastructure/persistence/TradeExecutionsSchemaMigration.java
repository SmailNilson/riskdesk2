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
import java.util.List;

/**
 * One-shot, idempotent migration for the legacy {@code NOT NULL} constraints on the
 * review-related columns of {@code trade_executions}: {@code mentor_signal_review_id},
 * {@code review_alert_key} and {@code review_revision}. All three became nullable on the
 * JPA entity at PR #303 (auto-armed quant rows and, later, WTX_AUTO rows carry a null
 * mentor review) — but Hibernate {@code ddl-auto=update} never drops an existing
 * {@code NOT NULL} constraint, so on any DB created before PR #303 every WTX_AUTO /
 * QUANT_AUTO_ARM insert fails at the row level with
 * {@code null value in column "<column>" ... violates not-null constraint} before any
 * IBKR call. This made every WTX auto-execution surface as {@code routingOutcome=FAILED}.
 *
 * <p>Idempotent: each column is migrated only if still {@code NOT NULL}; a fresh or
 * already-migrated DB is a no-op. Runs BEFORE the JPA {@code EntityManagerFactory}
 * (ordered via {@link TradeExecutionsSchemaMigrationDependsOnPostProcessor}) so the very
 * first candle-close after startup can no longer hit any of the legacy constraints.</p>
 */
@Component
public class TradeExecutionsSchemaMigration {

    private static final Logger log = LoggerFactory.getLogger(TradeExecutionsSchemaMigration.class);
    private static final String TABLE = "trade_executions";

    /**
     * Columns the JPA entity declares nullable since PR #303 — every one of them must be
     * relaxed, or the very next NOT NULL column would still reject WTX_AUTO inserts and
     * leave auto-execution silently broken.
     */
    private static final List<String> REVIEW_COLUMNS =
            List.of("mentor_signal_review_id", "review_alert_key", "review_revision");

    private final DataSource dataSource;

    public TradeExecutionsSchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void dropLegacyNotNullConstraints() {
        try (Connection connection = dataSource.getConnection()) {
            for (String column : REVIEW_COLUMNS) {
                dropNotNullIfPresent(connection, column);
            }
        } catch (SQLException e) {
            // Don't hard-fail startup on the check — let Hibernate proceed and surface
            // any real schema problem on its own.
            log.warn("Could not verify/drop legacy NOT NULL on {} review columns — letting "
                    + "Hibernate proceed: {}", TABLE, e.getMessage());
        }
    }

    private void dropNotNullIfPresent(Connection connection, String column) throws SQLException {
        if (!columnIsNotNull(connection, column)) {
            return; // already nullable or column absent — nothing to do
        }
        try (Statement statement = connection.createStatement()) {
            // ALTER ... DROP NOT NULL is supported by Postgres and H2 alike.
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN " + column + " DROP NOT NULL");
        }
        log.warn("Dropped legacy NOT NULL constraint on {}.{} — WTX_AUTO / QUANT_AUTO_ARM "
                + "executions can now persist without it.", TABLE, column);
    }

    /** True when the column exists AND is declared NOT NULL. False on a missing table/column too. */
    private boolean columnIsNotNull(Connection connection, String column) throws SQLException {
        String sql = "SELECT is_nullable FROM information_schema.columns "
                + "WHERE LOWER(table_name) = ? AND LOWER(column_name) = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, TABLE);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false; // column missing — nothing to migrate
                }
                // information_schema.is_nullable is 'YES' or 'NO' (SQL standard)
                return "NO".equalsIgnoreCase(rs.getString(1));
            }
        }
    }
}
