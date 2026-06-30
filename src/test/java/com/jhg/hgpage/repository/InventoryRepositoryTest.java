package com.jhg.hgpage.repository;

import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.wms.repository.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inventory를 Product 객체그래프 없이 productId만으로 단독 영속/조회한다.
 * (구 ProductInventoryPersistenceTest의 cascade 검증을 대체)
 */
@DataJpaTest
class InventoryRepositoryTest {

    @Autowired TestEntityManager em;
    @Autowired InventoryRepository inventoryRepository;

    private Long persistInventory(long productId, int onHand) {
        Inventory inv = Inventory.create(productId);
        inv.setOnHandQty(onHand);
        inv.setReservedQty(0);
        return em.persistAndFlush(inv).getId();
    }

    @Test
    void productId로_재고를_단독_조회한다() {
        persistInventory(1L, 30);
        em.clear();

        Inventory found = inventoryRepository.findByProductId(1L).orElseThrow();

        assertThat(found.getProductId()).isEqualTo(1L);
        assertThat(found.getOnHandQty()).isEqualTo(30);
        assertThat(found.getAvailableQty()).isEqualTo(30);
    }

    @Test
    void 동일_productId로_재고를_중복_삽입하면_예외가_발생한다() {
        persistInventory(1L, 10);

        // TestEntityManager는 Spring 예외 변환 레이어를 거치지 않아 Hibernate 예외가 직접 발생한다.
        assertThatThrownBy(() -> persistInventory(1L, 20))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void productId_묶음으로_재고를_일괄_조회한다() {
        persistInventory(1L, 10);
        persistInventory(2L, 20);
        persistInventory(3L, 30);
        em.clear();

        List<Inventory> found = inventoryRepository.findByProductIdIn(List.of(1L, 3L));

        assertThat(found).extracting(Inventory::getProductId)
                .containsExactlyInAnyOrder(1L, 3L);
    }
}
