package com.jhg.hgpage;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ddl-auto: update 환경에서는 앱 재시작마다 init()이 다시 불리므로,
 * 이미 시드된 DB에 중복 시드(이메일 unique 충돌로 기동 실패)가 없어야 한다.
 */
@DataJpaTest
class InitDbTest {

    @Autowired EntityManager em;

    @Test
    void 이미_시드된_DB에는_다시_시드하지_않는다() {
        initDb.initService service = new initDb.initService(em);
        initDb db = new initDb(service);

        db.init();
        db.init();

        Long accounts = em.createQuery("select count(a) from Account a", Long.class).getSingleResult();
        Long products = em.createQuery("select count(p) from Product p", Long.class).getSingleResult();
        assertThat(accounts).isEqualTo(2L);
        assertThat(products).isEqualTo(20L);
    }
}
