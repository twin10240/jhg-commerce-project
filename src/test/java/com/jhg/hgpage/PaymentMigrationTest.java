package com.jhg.hgpage;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentMigrationTest {

    @Test
    void V1_V4_V6_V7_V8이_결제와_주문처리_스키마를_만든다() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:payment-migration;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=true;DEFAULT_NULL_ORDERING=HIGH",
                "sa", "");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("1"))
                .load()
                .migrate();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V4__orders_add_version.sql"),
                new ClassPathResource("db/migration/V6__add_payments_and_order_processing.sql"),
                new ClassPathResource("db/migration/V7__add_cancellation_attempt_count.sql"))
                .execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        LocalDateTime requestedAt = LocalDateTime.of(2026, 8, 15, 12, 0);
        jdbcTemplate.update("insert into orders " +
                        "(order_id, status, cancellation_release_required, cancellation_requested_at, " +
                        "cancellation_attempt_count) values (?, 'CANCEL_REQUESTED', ?, ?, ?)",
                101L, true, Timestamp.valueOf(requestedAt), 4);
        jdbcTemplate.update("insert into orders " +
                        "(order_id, status, cancellation_release_required, cancellation_requested_at, " +
                        "cancellation_attempt_count) values (?, 'CANCEL_REQUESTED', ?, ?, ?)",
                102L, true, Timestamp.valueOf(requestedAt.plusMinutes(1)), 5);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V8__add_cancellation_retry_review.sql"))
                .execute(dataSource);
        jdbcTemplate.update("insert into orders " +
                        "(order_id, status, cancellation_release_required, cancellation_requested_at, " +
                        "cancellation_attempt_count, cancellation_failure_code) " +
                        "values (?, 'CANCEL_REQUESTED', ?, ?, ?, ?)",
                103L, true, Timestamp.valueOf(requestedAt.plusMinutes(2)), 5, "WMS_503");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V8__add_cancellation_retry_review.sql"))
                .execute(dataSource);

        DatabaseMetaData metadata = dataSource.getConnection().getMetaData();
        assertThat(tableNames(metadata)).contains("payment", "payment_attempt", "refund_request");
        assertThat(columnNames(metadata, "orders")).contains(
                "allocation_attempt_count", "next_allocation_attempt_at", "allocation_failure_code",
                "allocation_processing_at", "cancellation_release_required", "cancellation_requested_at",
                "cancellation_processing_at", "cancellation_attempt_count",
                "cancellation_next_attempt_at", "cancellation_failure_code");
        assertThat(uniqueIndexColumns(metadata, "payment")).contains("order_id");
        assertThat(uniqueIndexColumns(metadata, "payment_attempt")).contains("request_key");
        assertThat(uniqueIndexColumns(metadata, "refund_request")).contains("request_key", "source_type,source_id");
        assertThat(indexColumns(metadata, "payment_attempt")).contains("status,next_attempt_at,payment_attempt_id");
        assertThat(indexColumns(metadata, "refund_request")).contains("status,next_attempt_at,refund_request_id");
        assertThat(indexColumns(metadata, "orders"))
                .contains("status,cancellation_next_attempt_at,order_id");
        assertThat(jdbcTemplate.queryForObject(
                "select cancellation_next_attempt_at from orders where order_id = 101", LocalDateTime.class))
                .isEqualTo(requestedAt);
        assertThat(jdbcTemplate.queryForList(
                "select cancellation_next_attempt_at from orders where order_id in (102, 103)", LocalDateTime.class))
                .containsExactly((LocalDateTime) null, null);
        assertThat(jdbcTemplate.queryForList(
                "select cancellation_failure_code from orders where order_id in (102, 103) order by order_id",
                String.class))
                .containsExactly("WMS_RETRY_EXHAUSTED", "WMS_503");
    }

    private Set<String> tableNames(DatabaseMetaData metadata) throws Exception {
        Set<String> tables = new HashSet<>();
        try (ResultSet result = metadata.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (result.next()) {
                tables.add(result.getString("TABLE_NAME").toLowerCase());
            }
        }
        return tables;
    }

    private Set<String> columnNames(DatabaseMetaData metadata, String table) throws Exception {
        Set<String> columns = new HashSet<>();
        try (ResultSet result = metadata.getColumns(null, null, table, "%")) {
            while (result.next()) {
                columns.add(result.getString("COLUMN_NAME").toLowerCase());
            }
        }
        return columns;
    }

    private Set<String> uniqueIndexColumns(DatabaseMetaData metadata, String table) throws Exception {
        return indexColumns(metadata, table, true);
    }

    private Set<String> indexColumns(DatabaseMetaData metadata, String table) throws Exception {
        return indexColumns(metadata, table, false);
    }

    private Set<String> indexColumns(DatabaseMetaData metadata, String table, boolean uniqueOnly) throws Exception {
        java.util.Map<String, java.util.TreeMap<Short, String>> indexes = new java.util.HashMap<>();
        try (ResultSet result = metadata.getIndexInfo(null, null, table, uniqueOnly, false)) {
            while (result.next()) {
                String indexName = result.getString("INDEX_NAME");
                String columnName = result.getString("COLUMN_NAME");
                if (indexName != null && columnName != null) {
                    indexes.computeIfAbsent(indexName, ignored -> new java.util.TreeMap<>())
                            .put(result.getShort("ORDINAL_POSITION"), columnName.toLowerCase());
                }
            }
        }
        Set<String> columns = new HashSet<>();
        indexes.values().forEach(index -> columns.add(String.join(",", index.values())));
        return columns;
    }
}
