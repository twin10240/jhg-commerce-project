package com.jhg.hgpage.domain;

import com.jhg.hgpage.wms.domain.Inventory;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inventory 낙관적 락 검증. 임베디드 H2로 실제 JPA 버전 관리 동작을 확인한다.
 */
@DataJpaTest
class InventoryOptimisticLockTest {

    @Autowired TestEntityManager em;

    private Long persistInventory(int onHandQty) {
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(onHandQty);
        Long id = em.persistAndFlush(inventory).getId();
        em.clear();
        return id;
    }

    @Test
    void 재고를_수정하면_version이_증가한다() {
        Long id = persistInventory(10);

        Inventory inventory = em.find(Inventory.class, id);
        long before = inventory.getVersion();

        inventory.setOnHandQty(7);
        em.flush();

        assertThat(inventory.getVersion()).isEqualTo(before + 1);
    }

    @Test
    void 다른_수정이_먼저_커밋되면_늦은_수정은_충돌예외가_발생한다() {
        Long id = persistInventory(10);

        // 사용자 A: 재고를 읽어둔 채 대기 (detached, stale version)
        Inventory stale = em.find(Inventory.class, id);
        em.clear();

        // 사용자 B: 먼저 재고를 차감하고 커밋 → version 증가
        Inventory winner = em.find(Inventory.class, id);
        winner.setOnHandQty(9);
        em.flush();
        em.clear();

        // 사용자 A: 낡은 version으로 차감 시도 → 충돌
        stale.setOnHandQty(8);
        assertThatThrownBy(() -> {
            em.getEntityManager().merge(stale);
            em.flush();
        }).isInstanceOf(OptimisticLockException.class);
    }
}
