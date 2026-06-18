package com.jhg.hgpage.repository;

import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.catalog.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Product ↔ Inventory(1:1, cascade ALL) 영속성 검증 — 상품을 저장하면
 * 연관 재고가 함께 저장되고 양쪽 모두 식별자가 생성된다.
 * (구 ProductServiceTest: assertion 없이 출력만 하고 @Rollback(false)로 DB를 오염시키던 것을 교체)
 */
@DataJpaTest
class ProductInventoryPersistenceTest {

    @Autowired TestEntityManager em;

    @Test
    void 상품을_저장하면_연관_재고도_cascade로_함께_저장된다() {
        Product product = new Product();
        product.setName("상품");
        product.setPrice(1000);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(100);
        product.setInventory(inventory);

        Product saved = em.persistFlushFind(product);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getInventory()).isNotNull();
        assertThat(saved.getInventory().getId()).isNotNull();
        assertThat(saved.getInventory().getOnHandQty()).isEqualTo(100);
        assertThat(saved.getInventory().getAvailableQty()).isEqualTo(100);
    }
}
