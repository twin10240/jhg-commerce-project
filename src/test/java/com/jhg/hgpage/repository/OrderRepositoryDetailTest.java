package com.jhg.hgpage.repository;

import com.jhg.hgpage.config.QueryDslConfig;
import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Delivery;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Order;
import com.jhg.hgpage.domain.OrderItem;
import com.jhg.hgpage.catalog.Product;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주문 상세 페이지용 단건 조회(QueryDSL) — 영속성 컨텍스트를 비운 뒤에도
 * member/delivery/orderItems/product가 fetch join으로 모두 로딩돼 있어야 한다.
 */
@DataJpaTest
@Import({QueryDslConfig.class, OrderRepositoryQuery.class})
class OrderRepositoryDetailTest {

    @Autowired OrderRepositoryQuery orderRepositoryQuery;
    @Autowired TestEntityManager em;

    private Product newProduct(String name, int price) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(price);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(10);
        product.setInventory(inventory);
        em.persist(product);
        return product;
    }

    private Order saveOrder(OrderItem... orderItems) {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        em.persist(member);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery, orderItems);
        em.persist(order);
        em.flush();
        em.clear();
        return order;
    }

    @Test
    void 주문상세를_연관엔티티와_함께_단건_조회한다() {
        Product product = newProduct("테스트상품", 10000);
        Order order = saveOrder(OrderItem.createOrderItem(product, product.getPrice(), 2));

        Order found = orderRepositoryQuery.findDetailById(order.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(found.getMember())).isTrue();
        assertThat(Hibernate.isInitialized(found.getDelivery())).isTrue();
        assertThat(Hibernate.isInitialized(found.getOrderItems())).isTrue();
        assertThat(Hibernate.isInitialized(found.getOrderItems().get(0).getProduct())).isTrue();
        assertThat(found.getTotalPrice()).isEqualTo(20000);
    }

    @Test
    void 상품이_여러개인_주문도_단건으로_조회된다() {
        // 1:N fetch join은 SQL 행이 상품 수만큼 늘어나므로, 단건 조회가 중복 행에 안전해야 한다
        Product a = newProduct("테스트상품A", 10000);
        Product b = newProduct("테스트상품B", 12000);
        Order order = saveOrder(
                OrderItem.createOrderItem(a, a.getPrice(), 1),
                OrderItem.createOrderItem(b, b.getPrice(), 3));

        Order found = orderRepositoryQuery.findDetailById(order.getId()).orElseThrow();

        assertThat(found.getOrderItems()).hasSize(2);
        assertThat(found.getTotalPrice()).isEqualTo(46000);
    }

    @Test
    void 없는_주문은_빈_Optional을_반환한다() {
        assertThat(orderRepositoryQuery.findDetailById(999L)).isEmpty();
    }
}
