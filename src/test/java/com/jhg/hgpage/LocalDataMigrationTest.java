package com.jhg.hgpage;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Connection;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDataMigrationTest {

    @Test
    void 로컬_데이터_마이그레이션은_기존_COMP를_SHIPPED로_보존한다() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:local-data-migration;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("""
                    create table delivery (
                        delivery_id bigint primary key,
                        status enum ('COMP', 'READY'))
                    """);
            connection.createStatement().execute("insert into delivery values (1, 'COMP')");
        }

        new ResourceDatabasePopulator(new ClassPathResource("db/local-data-migration.sql")).execute(dataSource);

        try (Connection connection = dataSource.getConnection();
             ResultSet result = connection.createStatement()
                     .executeQuery("select status from delivery where delivery_id = 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("SHIPPED");
        }
    }

    @Test
    void 로컬_데이터_마이그레이션은_신규_H2_enum_스키마에서도_실행된다() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:local-enum-data-migration;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("""
                    create table delivery (
                        delivery_id bigint primary key,
                        status enum ('DELIVERED', 'READY', 'SHIPPED'))
                    """);
            connection.createStatement().execute("insert into delivery values (1, 'READY')");
        }

        new ResourceDatabasePopulator(new ClassPathResource("db/local-data-migration.sql")).execute(dataSource);

        try (Connection connection = dataSource.getConnection();
             ResultSet result = connection.createStatement()
                     .executeQuery("select status from delivery where delivery_id = 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("READY");
        }
    }

    @Test
    void 로컬_반품_상태_enum에_승인대기와_반려를_추가할_수_있다() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:local-return-enum-migration;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("""
                    create table delivery (
                        delivery_id bigint primary key,
                        status enum ('DELIVERED', 'READY', 'SHIPPED'))
                    """);
            connection.createStatement().execute("""
                    create table customer_return (
                        customer_return_id bigint primary key,
                        status enum ('CANCELLED', 'COMPLETED', 'PENDING_SUBMISSION',
                                     'RECEIVED', 'REQUESTED', 'SUBMISSION_FAILED'))
                    """);
            connection.createStatement().execute("insert into customer_return values (1, 'REQUESTED')");
        }

        new ResourceDatabasePopulator(new ClassPathResource("db/local-data-migration.sql")).execute(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("insert into customer_return values (2, 'PENDING_APPROVAL')");
            connection.createStatement().execute("insert into customer_return values (3, 'REJECTED')");
            try (ResultSet result = connection.createStatement().executeQuery(
                    "select status from customer_return order by customer_return_id")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("REQUESTED");
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("PENDING_APPROVAL");
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("REJECTED");
            }
        }
    }

    @Test
    void 로컬_반품의_RMA번호는_requestKey가_다르면_중복될_수_있다() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:local-return-rma-migration;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("""
                    create table delivery (
                        delivery_id bigint primary key,
                        status varchar(20))
                    """);
            connection.createStatement().execute("""
                    create table customer_return (
                        customer_return_id bigint primary key,
                        request_key uuid not null unique,
                        rma_id bigint,
                        status varchar(30),
                        constraint uq_customer_return_rma_id unique (rma_id))
                    """);
            connection.createStatement().execute(
                    "insert into customer_return values (1, random_uuid(), 2, 'COMPLETED')");
        }

        new ResourceDatabasePopulator(new ClassPathResource("db/local-data-migration.sql")).execute(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute(
                    "insert into customer_return values (2, random_uuid(), 2, 'PENDING_SUBMISSION')");
            try (ResultSet result = connection.createStatement()
                    .executeQuery("select count(*) from customer_return where rma_id = 2")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(2);
            }
        }
    }
}
