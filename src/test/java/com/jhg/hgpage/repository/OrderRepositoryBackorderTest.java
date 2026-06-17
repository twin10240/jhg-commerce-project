package com.jhg.hgpage.repository;

import com.jhg.hgpage.config.QueryDslConfig;
import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Delivery;
import com.jhg.hgpage.domain.Inventory;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Order;
import com.jhg.hgpage.domain.OrderItem;
import com.jhg.hgpage.domain.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 입고 시 백오더 자동 할당용 조회 — 해당 상품을 포함하는 BACKORDERED 주문을
 * 오래된 순으로 반환하며, 매칭되지 않는 라인까지 전부 로딩돼 있어야 한다
 * (할당은 주문의 모든 라인 가용성을 봐야 하므로).
 */
@DataJpaTest
@Import({QueryDslConfig.class, OrderRepositoryQuery.class})
class OrderRepositoryBackorderTest {

    @Autowired OrderRepositoryQuery orderRepositoryQuery;
    @Autowired TestEntityManager em;

    private Product newProduct(String name, int stock) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(10000);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(stock);
        product.setInventory(inventory);
        em.persist(product);
        return product;
    }

    private Order saveAllocatedOrder(OrderItem... items) {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        em.persist(member);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery, items);
        // 할당(allocate)이 서비스로 이동했으므로, 조회 테스트용 상태/예약은 가용성 판정을 직접 재현해 만든다.
        boolean allAvailable = Arrays.stream(items)
                .allMatch(item -> item.getProduct().getInventory().getAvailableQty() >= item.getCount());
        if (allAvailable) {
            for (OrderItem item : items) {
                item.getProduct().getInventory().reserve(item.getCount());
            }
            order.markOrdered();
        } else {
            order.markBackordered();
        }
        em.persist(order);
        return order;
    }

    @Test
    void 해당_상품을_포함한_백오더_주문만_오래된_순으로_반환한다() {
        Product scarce = newProduct("부족상품", 0);
        Product plenty = newProduct("여유상품", 100);

        Order backorder1 = saveAllocatedOrder(OrderItem.createOrderItem(scarce, 10000, 5)); // BACKORDERED
        Order normal = saveAllocatedOrder(OrderItem.createOrderItem(plenty, 10000, 1));     // ORDER
        Order backorder2 = saveAllocatedOrder(OrderItem.createOrderItem(scarce, 10000, 3)); // BACKORDERED
        em.flush();
        em.clear();

        List<Order> result = orderRepositoryQuery.findBackordersContaining(List.of(scarce.getId()));

        assertThat(result).extracting(Order::getId)
                .containsExactly(backorder1.getId(), backorder2.getId()); // FIFO, ORDER 주문 제외
    }

    @Test
    void 매칭되지_않는_라인까지_주문의_모든_라인이_로딩된다() {
        Product scarce = newProduct("부족상품", 0);
        Product other = newProduct("다른상품", 100);

        // 부족상품 + 다른상품을 함께 담은 백오더
        saveAllocatedOrder(
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
        Product plenty = newProduct("여유상품", 100);
        saveAllocatedOrder(OrderItem.createOrderItem(plenty, 10000, 1)); // ORDER
        em.flush();
        em.clear();

        assertThat(orderRepositoryQuery.findBackordersContaining(List.of(plenty.getId()))).isEmpty();
    }
}
