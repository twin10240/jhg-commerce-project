package com.jhg.hgpage;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1 마이그레이션 SQL이 오류 없이 실행되는지 검증한다.
 * H2 PostgreSQL 모드를 사용해 Spring 컨텍스트 없이 Flyway만 구동.
 * 한계: H2 == PostgreSQL이 아니므로 타입/함수 호환성은 Railway 배포로 최종 확인.
 */
class FlywayMigrationTest {

    @Test
    void V1_마이그레이션이_H2_PostgreSQL모드에서_오류_없이_적용된다() {
        Flyway flyway = Flyway.configure()
                .dataSource(
                    "jdbc:h2:mem:flyway-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=true;DEFAULT_NULL_ORDERING=HIGH",
                    "sa", "")
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();

        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).hasSize(2);
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(applied[0].getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(applied[1].getVersion().getVersion()).isEqualTo("2");
        assertThat(applied[1].getState()).isEqualTo(MigrationState.SUCCESS);
    }
}
