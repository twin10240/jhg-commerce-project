package com.jhg.hgpage;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 마이그레이션 SQL이 오류 없이 실행되는지 검증한다.
 * H2 PostgreSQL 모드를 사용해 Spring 컨텍스트 없이 Flyway만 구동.
 *
 * 한계:
 * - V2는 prod(PostgreSQL) 전용 데이터 정리를 포함한다(구 product.inventory_id 역산).
 *   H2 신규 스키마에는 product.inventory_id가 없어 V2 SQL이 파싱 오류를 낸다.
 *   따라서 이 테스트는 target=V1로 제한한다.
 * - V2 이후 마이그레이션의 H2 호환성은 Railway 배포로 최종 확인한다.
 */
class FlywayMigrationTest {

    @Test
    void V1_마이그레이션이_H2_PostgreSQL모드에서_오류_없이_적용된다() {
        Flyway flyway = Flyway.configure()
                .dataSource(
                    "jdbc:h2:mem:flyway-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=true;DEFAULT_NULL_ORDERING=HIGH",
                    "sa", "")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("1"))
                .load();

        flyway.migrate();

        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).hasSize(1);
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(applied[0].getState()).isEqualTo(MigrationState.SUCCESS);
    }
}
