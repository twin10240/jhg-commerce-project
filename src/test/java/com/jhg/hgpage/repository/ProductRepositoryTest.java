package com.jhg.hgpage.repository;

import com.jhg.hgpage.domain.Inventory;
import com.jhg.hgpage.domain.Product;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ProductRepositoryTest {

    @Autowired TestEntityManager em;
    @Autowired ProductRepository productRepository;

    private void persistProductWithStock(String name, int stock) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(10000);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(stock);
        product.setInventory(inventory); // cascade ALL로 함께 저장
        em.persist(product);
    }

    @Test
    void findAllWithInventory는_재고를_fetch_join으로_함께_로드한다() {
        persistProductWithStock("상품1", 10);
        persistProductWithStock("상품2", 20);
        em.flush();
        em.clear();

        List<Product> products = productRepository.findAllWithInventory();

        assertThat(products).hasSize(2);
        // fetch join이 아니면 LAZY 프록시라 초기화되지 않은 상태여야 한다
        products.forEach(product ->
                assertThat(Hibernate.isInitialized(product.getInventory())).isTrue());
    }
}
