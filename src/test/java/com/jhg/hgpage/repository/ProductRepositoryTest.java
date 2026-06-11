package com.jhg.hgpage.repository;

import com.jhg.hgpage.domain.Inventory;
import com.jhg.hgpage.domain.Product;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

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

    @Test
    void findPageWithInventory는_재고를_fetch_join으로_함께_로드한다() {
        persistProductWithStock("상품1", 10);
        persistProductWithStock("상품2", 20);
        em.flush();
        em.clear();

        Page<Product> page = productRepository.findPageWithInventory(PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(2);
        page.getContent().forEach(product ->
                assertThat(Hibernate.isInitialized(product.getInventory())).isTrue());
    }

    @Test
    void findPageByNameWithInventory는_키워드로_거르고_재고를_함께_로드한다() {
        persistProductWithStock("허니라떼", 10);
        persistProductWithStock("아메리카노", 20);
        em.flush();
        em.clear();

        Page<Product> page = productRepository.findPageByNameWithInventory("라떼", PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getName()).isEqualTo("허니라떼");
        assertThat(Hibernate.isInitialized(page.getContent().get(0).getInventory())).isTrue();
    }
}
