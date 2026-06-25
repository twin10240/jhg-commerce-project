package com.jhg.hgpage;

import com.jhg.hgpage.oms.domain.Account;
import com.jhg.hgpage.wms.repository.InventoryRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ddl-auto: update 환경에서는 앱 재시작마다 init()이 다시 불리므로,
 * 이미 시드된 DB에 중복 시드(이메일 unique 충돌로 기동 실패)가 없어야 한다.
 * 또한 관리자 비밀번호는 코드에 박지 않고 주입받은 값(운영은 ADMIN_PASSWORD env)으로 시드한다.
 */
@DataJpaTest
class InitDbTest {

    @Autowired EntityManager em;
    @Autowired InventoryRepository inventoryRepository;

    @Test
    void 이미_시드된_DB에는_다시_시드하지_않는다() {
        initDb.initService service = new initDb.initService(em, inventoryRepository, "1111");
        initDb db = new initDb(service);

        db.init();
        db.init();

        Long accounts = em.createQuery("select count(a) from Account a", Long.class).getSingleResult();
        Long products = em.createQuery("select count(p) from Product p", Long.class).getSingleResult();
        assertThat(accounts).isEqualTo(2L);
        assertThat(products).isEqualTo(20L);
    }

    @Test
    void 관리자_비밀번호는_주입받은_값으로_시드된다() {
        initDb.initService service = new initDb.initService(em, inventoryRepository, "s3cret-from-env");
        initDb db = new initDb(service);

        db.init();

        Account admin = em.createQuery(
                "select a from Account a where a.email = 'admin@admin.com'", Account.class).getSingleResult();
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        assertThat(encoder.matches("s3cret-from-env", admin.getPassword())).isTrue();
        assertThat(encoder.matches("1111", admin.getPassword())).isFalse(); // 더 이상 하드코딩 1111 아님
    }
}
