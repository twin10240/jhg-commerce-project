package com.jhg.hgpage.repository;

import com.jhg.hgpage.config.QueryDslConfig;
import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Delivery;
import com.jhg.hgpage.domain.Inventory;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 배송 관리 목록 — 전체 주문을 member/delivery fetch join으로 조회한다.
 * (orderItems는 default_batch_fetch_size가 IN 쿼리로 묶는다)
 */
@DataJpaTest
@Import({QueryDslConfig.class, OrderRepositoryQuery.class})
class OrderRepositoryAdminListTest {

    @Autowired OrderRepositoryQuery orderRepositoryQuery;
    @Autowired TestEntityManager em;

    private Order saveOrderOf(String memberName) {
        Member member = Member.createUser(memberName, "010-0000-0000", new Address("서울", "관악구", "500"));
        em.persist(member);
        Product product = new Product();
        product.setName("테스트상품");
        product.setPrice(10000);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(10);
        product.setInventory(inventory);
        em.persist(product);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery, OrderItem.createOrderItem(product, product.getPrice(), 1));
        em.persist(order);
        return order;
    }

    @Test
    void 전체_주문을_회원_배송과_함께_최신순으로_조회한다() {
        Order first = saveOrderOf("회원A");
        Order second = saveOrderOf("회원B");
        em.flush();
        em.clear();

        List<Order> orders = orderRepositoryQuery.findAllForAdmin();

        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).getId()).isEqualTo(second.getId()); // 최신(id 큰 것) 먼저
        assertThat(orders.get(1).getId()).isEqualTo(first.getId());
        assertThat(Hibernate.isInitialized(orders.get(0).getMember())).isTrue();
        assertThat(Hibernate.isInitialized(orders.get(0).getDelivery())).isTrue();
    }
}
