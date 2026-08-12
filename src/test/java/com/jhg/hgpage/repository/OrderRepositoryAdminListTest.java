package com.jhg.hgpage.repository;

import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import com.jhg.hgpage.config.QueryDslConfig;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
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
        em.persist(product);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery, OrderItem.createOrderItem(product, product.getPrice(), 1));
        em.persist(order);
        return order;
    }

    @Test
    void 미처리_우선으로_그룹화하고_진행중은_오래된순_종료건은_최신순으로_조회한다() {
        Order shippedOld = saveOrderOf("출고A");
        shippedOld.ship();
        Order readyOld = saveOrderOf("배송대기A");
        Order canceledOld = saveOrderOf("취소A");
        canceledOld.cancel();
        Order backorderOld = saveOrderOf("입고대기A");
        backorderOld.markBackordered();
        Order readyNew = saveOrderOf("배송대기B");
        Order backorderNew = saveOrderOf("입고대기B");
        backorderNew.markBackordered();
        Order shippedNew = saveOrderOf("출고B");
        shippedNew.ship();
        Order canceledNew = saveOrderOf("취소B");
        canceledNew.cancel();
        em.flush();
        em.clear();

        List<Order> orders = orderRepositoryQuery.findAllForAdmin();

        assertThat(orders).extracting(Order::getId).containsExactly(
                readyOld.getId(), readyNew.getId(),
                backorderOld.getId(), backorderNew.getId(),
                shippedNew.getId(), shippedOld.getId(),
                canceledNew.getId(), canceledOld.getId());
        assertThat(Hibernate.isInitialized(orders.get(0).getMember())).isTrue();
        assertThat(Hibernate.isInitialized(orders.get(0).getDelivery())).isTrue();
    }
}
