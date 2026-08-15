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
}
