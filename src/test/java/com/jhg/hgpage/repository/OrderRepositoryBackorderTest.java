package com.jhg.hgpage.repository;

import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import com.jhg.hgpage.config.QueryDslConfig;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 입고 시 백오더 자동 할당용 조회 — 해당 상품을 포함하는 BACKORDERED 주문을
 * 오래된 순으로 반환하며, 매칭되지 않는 라인까지 전부 로딩돼 있어야 한다
 * (할당은 주문의 모든 라인 가용성을 봐야 하므로).
 *
 * <p>이 조회는 주문 상태(BACKORDERED/ORDER)만 본다 — 재고와 무관하므로
 * 셋업도 할당 결과 상태를 직접 표시한다(예약/가용성 판정은 서비스 책임).
 */
@DataJpaTest
@Import({QueryDslConfig.class, OrderRepositoryQuery.class})
class OrderRepositoryBackorderTest {

    @Autowired OrderRepositoryQuery orderRepositoryQuery;
    @Autowired TestEntityManager em;

    private Product newProduct(String name) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(10000);
        em.persist(product);
        return product;
    }

    private Order newOrder(OrderItem... items) {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        em.persist(member);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        return Order.createOrder(member, delivery, items);
    }

    private Order saveBackorder(OrderItem... items) {
        Order order = newOrder(items);
        order.markBackordered();
        em.persist(order);
        return order;
    }

    private Order saveOrdered(OrderItem... items) {
        Order order = newOrder(items);
        order.markOrdered();
        em.persist(order);
        return order;
    }

    @Test
    void 해당_상품을_포함한_백오더_주문만_오래된_순으로_반환한다() {
        Product scarce = newProduct("부족상품");
        Product plenty = newProduct("여유상품");

        Order backorder1 = saveBackorder(OrderItem.createOrderItem(scarce, 10000, 5));
        saveOrdered(OrderItem.createOrderItem(plenty, 10000, 1));               // ORDER (제외 대상)
        Order backorder2 = saveBackorder(OrderItem.createOrderItem(scarce, 10000, 3));
        em.flush();
        em.clear();

        List<Order> result = orderRepositoryQuery.findBackordersContaining(List.of(scarce.getId()));

        assertThat(result).extracting(Order::getId)
                .containsExactly(backorder1.getId(), backorder2.getId()); // FIFO, ORDER 주문 제외
    }

    @Test
    void 매칭되지_않는_라인까지_주문의_모든_라인이_로딩된다() {
        Product scarce = newProduct("부족상품");
        Product other = newProduct("다른상품");

        // 부족상품 + 다른상품을 함께 담은 백오더
        saveBackorder(
                OrderItem.createOrderItem(scarce, 10000, 5),
                OrderItem.createOrderItem(other, 10000, 2));
        em.flush();
        em.clear();

        List<Order> result = orderRepositoryQuery.findBackordersContaining(List.of(scarce.getId()));

        assertThat(result).hasSize(1);
        // where 조건이 컬렉션을 잘라먹으면 1개만 남는다 — 반드시 2개여야 할당이 안전하다
        assertThat(result.get(0).getOrderItems()).hasSize(2);
    }

    @Test
    void 해당_상품을_포함한_백오더가_없으면_빈_목록을_반환한다() {
        Product plenty = newProduct("여유상품");
        saveOrdered(OrderItem.createOrderItem(plenty, 10000, 1)); // ORDER
        em.flush();
        em.clear();

        assertThat(orderRepositoryQuery.findBackordersContaining(List.of(plenty.getId()))).isEmpty();
    }

    @Test
    void 백오더_주문의_상품id만_중복없이_반환한다() {
        Product scarce = newProduct("부족상품");
        Product other = newProduct("다른상품");
        Product plenty = newProduct("여유상품");

        saveBackorder(OrderItem.createOrderItem(scarce, 10000, 5),
                      OrderItem.createOrderItem(other, 10000, 2));
        saveBackorder(OrderItem.createOrderItem(scarce, 10000, 3)); // scarce 중복 — distinct 검증
        saveOrdered(OrderItem.createOrderItem(plenty, 10000, 1));   // ORDER — 제외 검증
        em.flush();
        em.clear();

        List<Long> result = orderRepositoryQuery.findBackorderedProductIds();

        assertThat(result).containsExactlyInAnyOrder(scarce.getId(), other.getId());
    }

    @Test
    void 기존_무결제와_PAID_백오더만_출고전_FIFO로_반환한다() {
        Product product = newProduct("부족상품");
        Order legacy = saveBackorder(OrderItem.createOrderItem(product, 10000, 1));
        legacy.setOrderDate(LocalDateTime.of(2026, 8, 15, 10, 0));
        Order nonPaid = saveBackorder(OrderItem.createOrderItem(product, 10000, 1));
        nonPaid.setOrderDate(LocalDateTime.of(2026, 8, 15, 11, 0));
        em.persist(Payment.create(nonPaid, nonPaid.getTotalPrice()));
        Order shipped = savePaidBackorder(product, LocalDateTime.of(2026, 8, 15, 10, 0));
        shipped.getDelivery().setStatus(DeliveryStatus.SHIPPED);
        Order paid = savePaidBackorder(product, LocalDateTime.of(2026, 8, 15, 12, 0));
        em.flush();
        em.clear();

        List<Order> result = orderRepositoryQuery.findPaidBackordersContaining(List.of(product.getId()));

        assertThat(result).extracting(Order::getId).containsExactly(legacy.getId(), paid.getId());
        assertThat(result).extracting(Order::getId).doesNotContain(nonPaid.getId(), shipped.getId());
    }

    @Test
    void 도래한_할당은_주문일과_id_FIFO로_조회한다() {
        Product product = newProduct("할당상품");
        LocalDateTime now = LocalDateTime.of(2026, 8, 15, 13, 0);
        Order firstId = saveAllocationPending(product, LocalDateTime.of(2026, 8, 15, 12, 0), now);
        Order oldest = saveAllocationPending(product, LocalDateTime.of(2026, 8, 15, 11, 0), now);
        Order lastId = saveAllocationPending(product, LocalDateTime.of(2026, 8, 15, 12, 0), now);
        em.flush();
        em.clear();

        assertThat(orderRepositoryQuery.findDueAllocationOrderIds(now))
                .containsExactly(oldest.getId(), firstId.getId(), lastId.getId());
    }

    private Order savePaidBackorder(Product product, LocalDateTime orderDate) {
        Order order = saveBackorder(OrderItem.createOrderItem(product, 10000, 1));
        order.setOrderDate(orderDate);
        Payment payment = Payment.create(order, order.getTotalPrice());
        payment.markPaid(orderDate);
        em.persist(payment);
        return order;
    }

    private Order saveAllocationPending(Product product, LocalDateTime orderDate, LocalDateTime dueAt) {
        Order order = newOrder(OrderItem.createOrderItem(product, 10000, 1));
        order.setOrderDate(orderDate);
        order.markPaymentPending();
        order.markAllocationPending();
        order.setNextAllocationAttemptAt(dueAt);
        em.persist(order);
        return order;
    }
}
