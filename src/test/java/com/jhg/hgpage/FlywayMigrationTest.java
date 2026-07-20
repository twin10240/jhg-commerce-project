package com.jhg.hgpage;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationTest {

    @Test
    void allMigrations_applyToCleanDatabase() {
        Flyway flyway = Flyway.configure()
                .dataSource(
                        "jdbc:h2:mem:flyway-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=true;DEFAULT_NULL_ORDERING=HIGH",
                        "sa", "")
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();

        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).hasSize(4);
        assertThat(applied).allMatch(info -> info.getState() == MigrationState.SUCCESS);
    }
}
