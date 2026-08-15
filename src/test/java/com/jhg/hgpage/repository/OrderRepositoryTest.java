package com.jhg.hgpage.repository;

import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import com.jhg.hgpage.config.QueryDslConfig;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.catalog.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회원의 주문 목록 조회(QueryDSL `findOrders`) — 본인 주문만, orderItems를 fetch join으로 함께 반환한다.
 * (구 OrderRepositoryTest: assertion 없이 시드 ID(2L)에 의존해 출력만 하던 것을 자체 데이터 + 단언으로 교체.
 *  구 테스트가 호출하던 `findOrdersByMemberId`는 미사용 메서드(#9)라 제거됐고, 실제 쓰이는 `findOrders`를 검증한다.)
 */
@DataJpaTest
@Import({QueryDslConfig.class, OrderRepositoryQuery.class})
class OrderRepositoryTest {

    @Autowired OrderRepositoryQuery orderRepositoryQuery;
    @Autowired TestEntityManager em;

    private static final Address ADDRESS = new Address("서울", "관악구", "500");

    private Product newProduct(String name, int price) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(price);
        em.persist(product);
        return product;
    }

    private Member newMember() {
        Member member = Member.createUser("테스터", "010-0000-0000", ADDRESS);
        em.persist(member);
        return member;
    }

    private Order saveOrder(Member member, OrderItem... orderItems) {
        Delivery delivery = new Delivery();
        delivery.setAddress(ADDRESS);
        Order order = Order.createOrder(member, delivery, orderItems);
        em.persist(order);
        return order;
    }

    @Test
    void 회원의_주문을_orderItems와_함께_조회한다() {
        Member member = newMember();
        Product product = newProduct("상품", 10000);
        saveOrder(member, OrderItem.createOrderItem(product, product.getPrice(), 2));
        em.flush();
        em.clear();

        List<Order> orders = orderRepositoryQuery.findOrders(member.getId());

        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getOrderItems()).hasSize(1);
        assertThat(orders.get(0).getTotalPrice()).isEqualTo(20000);
    }

    @Test
    void 다른_회원의_주문은_조회되지_않는다() {
        Member owner = newMember();
        Member other = newMember();
        Product product = newProduct("상품", 10000);
        saveOrder(owner, OrderItem.createOrderItem(product, product.getPrice(), 1));
        em.flush();
        em.clear();

        assertThat(orderRepositoryQuery.findOrders(other.getId())).isEmpty();
    }

    @Test
    void 진행중_주문을_최신순으로_먼저_보이고_종료_주문은_하단에_최신순으로_정렬한다() {
        Member member = newMember();
        Product product = newProduct("상품", 10000);

        Order completedOlder = saveOrder(member,
                OrderItem.createOrderItem(product, product.getPrice(), 1));
        completedOlder.setOrderDate(LocalDateTime.of(2026, 8, 1, 12, 0));
        completedOlder.ship();
        completedOlder.deliver();

        Order activeOlder = saveOrder(member,
                OrderItem.createOrderItem(product, product.getPrice(), 1));
        activeOlder.setOrderDate(LocalDateTime.of(2026, 8, 2, 12, 0));

        Order cancelledNewest = saveOrder(member,
                OrderItem.createOrderItem(product, product.getPrice(), 1));
        cancelledNewest.setOrderDate(LocalDateTime.of(2026, 8, 5, 12, 0));
        cancelledNewest.cancel();

        Order activeNewest = saveOrder(member,
                OrderItem.createOrderItem(product, product.getPrice(), 1));
        activeNewest.setOrderDate(LocalDateTime.of(2026, 8, 4, 12, 0));

        Order completedMiddle = saveOrder(member,
                OrderItem.createOrderItem(product, product.getPrice(), 1));
        completedMiddle.setOrderDate(LocalDateTime.of(2026, 8, 3, 12, 0));
        completedMiddle.ship();
        completedMiddle.deliver();
        em.flush();
        em.clear();

        assertThat(orderRepositoryQuery.findOrders(member.getId()))
                .extracting(Order::getId)
                .containsExactly(activeNewest.getId(), activeOlder.getId(), cancelledNewest.getId(),
                        completedMiddle.getId(), completedOlder.getId());
    }
}
