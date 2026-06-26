package com.jhg.hgpage.repository;

import com.jhg.hgpage.wms.repository.PurchaseOrderRepository;
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

    @Test
    void findAllWithItems는_품목을_fetch_join으로_함께_로드한다() {
        em.persist(PurchaseOrder.create("발주1", PurchaseOrderItem.create(1L, 5)));
        em.flush();
        em.clear();

        List<PurchaseOrder> orders = purchaseOrderRepository.findAllWithItems();

        assertThat(orders).hasSize(1);
        PurchaseOrder po = orders.get(0);
        assertThat(Hibernate.isInitialized(po.getItems())).isTrue();
        assertThat(po.getItems().get(0).getProductId()).isEqualTo(1L);
    }
}
