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

@Component
public class TradeSimulationsSchemaMigration {

    private static final Logger log = LoggerFactory.getLogger(TradeSimulationsSchemaMigration.class);
    private static final String TABLE = "trade_simulations";

    private final DataSource dataSource;

    public TradeSimulationsSchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void dropLegacyEnumChecks() {
        try (Connection connection = dataSource.getConnection()) {
            dropChecksForColumn(connection, "review_type");
            dropChecksForColumn(connection, "simulation_status");
        } catch (SQLException e) {
            log.warn("Could not verify/clean legacy constraints on {} — letting Hibernate proceed: {}",
                TABLE, e.getMessage());
        }
    }

    private void dropChecksForColumn(Connection connection, String column) throws SQLException {
        String sql = """
            select tc.constraint_name
            from information_schema.table_constraints tc
            join information_schema.constraint_column_usage ccu
              on tc.constraint_schema = ccu.constraint_schema
             and tc.constraint_name = ccu.constraint_name
            where tc.table_name = ?
              and tc.constraint_type = 'CHECK'
              and ccu.column_name = ?
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, TABLE);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String constraint = rs.getString("constraint_name");
                    try (PreparedStatement drop = connection.prepareStatement(
                        "alter table " + TABLE + " drop constraint if exists " + constraint)) {
                        drop.executeUpdate();
                        log.info("Dropped legacy {} check constraint {} on {}.{}",
                            TABLE, constraint, TABLE, column);
                    }
                }
            }
        }
    }
}
