package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.TradeExecutionEntity;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Idempotent migration that aligns the {@code trade_executions} DB schema with the JPA
 * entity's current nullability declarations.
 *
 * <p>The auto-execution paths (WTX_AUTO, QUANT_AUTO_ARM) legitimately set several columns
 * to {@code null} that were originally created {@code NOT NULL} for the mentor path
 * (review IDs, virtual SL/TP, …). Each PR that relaxed one of these columns on the entity
 * (PR #303 for the review fields, this PR for the virtual stop/take) has had to ship a
 * companion migration because Hibernate {@code ddl-auto=update} never drops an existing
 * {@code NOT NULL} constraint — so legacy DBs kept rejecting auto-execution inserts at
 * the row level before any IBKR call.</p>
 *
 * <p>Rather than hand-listing the columns (and missing the next drift), this migration
 * <strong>reflects on {@link TradeExecutionEntity}</strong>: every column the entity now
 * declares nullable is checked against {@code information_schema}; any column that is
 * still {@code NOT NULL} in the DB has its constraint dropped. Idempotent — a fresh or
 * already-aligned DB is a no-op. Runs BEFORE the JPA {@code EntityManagerFactory}
 * (ordered via {@link TradeExecutionsSchemaMigrationDependsOnPostProcessor}) so the very
 * first candle-close after startup never hits a legacy constraint.</p>
 */
@Component
public class TradeExecutionsSchemaMigration {

    private static final Logger log = LoggerFactory.getLogger(TradeExecutionsSchemaMigration.class);
    private static final String TABLE = "trade_executions";

    private final DataSource dataSource;

    public TradeExecutionsSchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void dropLegacyNotNullConstraints() {
        Set<String> entityNullableColumns = collectEntityNullableColumns();
        if (entityNullableColumns.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            for (String column : entityNullableColumns) {
                dropNotNullIfPresent(connection, column);
            }
        } catch (SQLException e) {
            // Don't hard-fail startup on the check — let Hibernate proceed and surface
            // any real schema problem on its own.
            log.warn("Could not verify/drop legacy NOT NULL constraints on {} — letting "
                    + "Hibernate proceed: {}", TABLE, e.getMessage());
        }
    }

    /**
     * Set of DB column names the entity now declares nullable. Skips {@code @Id},
     * {@code @Version}, {@code @Transient} and static/synthetic fields — those can't
     * legitimately be relaxed even if they happen to lack an explicit nullability.
     */
    private Set<String> collectEntityNullableColumns() {
        Set<String> columns = new LinkedHashSet<>();
        for (Field field : TradeExecutionEntity.class.getDeclaredFields()) {
            if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (field.isAnnotationPresent(Id.class)
                    || field.isAnnotationPresent(Version.class)
                    || field.isAnnotationPresent(Transient.class)) {
                continue;
            }
            Column column = field.getAnnotation(Column.class);
            // JPA default: nullable = true when @Column is absent or doesn't disallow it.
            boolean nullable = column == null || column.nullable();
            if (!nullable) {
                continue;
            }
            columns.add(resolveColumnName(field, column));
        }
        return columns;
    }

    /**
     * Column name resolution: explicit {@code @Column(name="…")} wins; otherwise
     * Spring Boot's default physical naming strategy converts camelCase to snake_case
     * (e.g. {@code virtualStopLoss} → {@code virtual_stop_loss}).
     */
    private String resolveColumnName(Field field, Column column) {
        if (column != null && !column.name().isBlank()) {
            return column.name().toLowerCase();
        }
        return camelToSnake(field.getName());
    }

    private String camelToSnake(String name) {
        StringBuilder sb = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void dropNotNullIfPresent(Connection connection, String column) throws SQLException {
        if (!columnIsNotNull(connection, column)) {
            return; // already nullable or column absent — nothing to do
        }
        try (Statement statement = connection.createStatement()) {
            // ALTER ... DROP NOT NULL is supported by Postgres and H2 alike.
            statement.execute("ALTER TABLE " + TABLE + " ALTER COLUMN " + column + " DROP NOT NULL");
        }
        log.warn("Dropped legacy NOT NULL constraint on {}.{} — the entity now declares it "
                + "nullable; auto-execution paths can persist null here.", TABLE, column);
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
