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
 * One-shot, idempotent migration for legacy {@code NOT NULL} constraints on
 * {@code trade_executions} columns that became nullable when the table started carrying
 * executions without a mentor review (PR #303 quant auto-arm, then WTX_AUTO).
 *
 * <p>The JPA entity has declared {@code mentor_signal_review_id} (and friends) nullable
 * for a while, but Hibernate {@code ddl-auto=update} never drops existing {@code NOT NULL}
 * constraints — so on any DB created before that change every insert with a null mentor
 * review id (every WTX_AUTO / QUANT_AUTO_ARM row) fails with
 * {@code null value in column "mentor_signal_review_id" ... violates not-null constraint}.
 * This caught every WTX auto-execution attempt as {@code FAILED}.</p>
 *
 * <p>Idempotent: runs only when the column is still {@code NOT NULL}; a fresh or
 * already-migrated DB is a no-op. Runs BEFORE the JPA {@code EntityManagerFactory}
 * (ordered via {@link TradeExecutionsSchemaMigrationDependsOnPostProcessor}) so the very
 * first candle-close after startup can no longer hit the legacy constraint.</p>
 */
@Component
public class TradeExecutionsSchemaMigration {

    private static final Logger log = LoggerFactory.getLogger(TradeExecutionsSchemaMigration.class);
    private static final String TABLE = "trade_executions";
    private static final String COLUMN = "mentor_signal_review_id";

    private final DataSource dataSource;

    public TradeExecutionsSchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void dropLegacyNotNullIfPresent() {
        try (Connection connection = dataSource.getConnection()) {
            if (!columnIsNotNull(connection)) {
                return; // already nullable or table/column absent — nothing to do
            }
            try (Statement statement = connection.createStatement()) {
                // ALTER ... DROP NOT NULL is supported by Postgres and H2 alike.
                statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN " + COLUMN + " DROP NOT NULL");
            }
            log.warn("Dropped legacy NOT NULL constraint on {}.{} — WTX_AUTO / QUANT_AUTO_ARM "
                    + "executions can now persist without a mentor review id.", TABLE, COLUMN);
        } catch (SQLException e) {
            // Don't hard-fail startup on the migration check — let Hibernate proceed and surface
            // any real schema problem on its own.
            log.warn("Could not verify/drop NOT NULL on {}.{} — letting Hibernate proceed: {}",
                    TABLE, COLUMN, e.getMessage());
        }
    }

    /** True when the column exists AND is declared NOT NULL. False on a missing table/column too. */
    private boolean columnIsNotNull(Connection connection) throws SQLException {
        String sql = "SELECT is_nullable FROM information_schema.columns "
                + "WHERE LOWER(table_name) = ? AND LOWER(column_name) = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, TABLE);
            ps.setString(2, COLUMN);
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
