package com.jhg.hgpage.repository;

import com.jhg.hgpage.wms.repository.PurchaseOrderRepository;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.wms.domain.PurchaseOrder;
import com.jhg.hgpage.wms.domain.PurchaseOrderItem;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PurchaseOrderRepositoryTest {

    @Autowired TestEntityManager em;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;

    private Product persistProduct(String name) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(10000);
        em.persist(product);
        return product;
    }

    @Test
    void findAllWithItems는_품목과_상품을_fetch_join으로_함께_로드한다() {
        Product product = persistProduct("상품1");
        em.persist(PurchaseOrder.create("발주1", PurchaseOrderItem.create(product, 5)));
        em.flush();
        em.clear();

        List<PurchaseOrder> orders = purchaseOrderRepository.findAllWithItems();

        assertThat(orders).hasSize(1);
        PurchaseOrder po = orders.get(0);
        assertThat(Hibernate.isInitialized(po.getItems())).isTrue();
        assertThat(Hibernate.isInitialized(po.getItems().get(0).getProduct())).isTrue();
        assertThat(po.getItems().get(0).getProduct().getName()).isEqualTo("상품1");
    }
}
