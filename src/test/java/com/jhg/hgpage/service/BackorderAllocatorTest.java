package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Delivery;
import com.jhg.hgpage.domain.Inventory;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Order;
import com.jhg.hgpage.domain.OrderItem;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.domain.enums.OrderStatus;
import com.jhg.hgpage.repository.OrderRepositoryQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 입고/재고증가 시 백오더 자동 할당 — 오래된 주문(FIFO)부터 가용분을 배정해
 * 가능해진 주문을 ORDER로 승격한다.
 */
@ExtendWith(MockitoExtension.class)
class BackorderAllocatorTest {

    @Mock OrderRepositoryQuery orderRepositoryQuery;
    @InjectMocks BackorderAllocator backorderAllocator;

    private Product productWithStock(int stock) {
        Product product = new Product();
        product.setId(1L);
        product.setName("상품");
        product.setPrice(10000);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(stock);
        product.setInventory(inventory);
        return product;
    }

    private Order backorderOf(Product product, int quantity) {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery,
                OrderItem.createOrderItem(product, product.getPrice(), quantity));
        order.allocate();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.BACKORDERED); // 전제 확인
        return order;
    }

    @Test
    void 가용해진_백오더를_ORDER로_승격하고_예약한다() {
        Product product = productWithStock(0);
        Order backorder = backorderOf(product, 3);
        product.getInventory().addOnHandQty(10); // 입고
        when(orderRepositoryQuery.findBackordersContaining(List.of(1L))).thenReturn(List.of(backorder));

        int promoted = backorderAllocator.allocate(List.of(1L));

        assertThat(promoted).isEqualTo(1);
        assertThat(backorder.getStatus()).isEqualTo(OrderStatus.ORDER);
        assertThat(product.getInventory().getReservedQty()).isEqualTo(3);
    }

    @Test
    void 오래된_주문부터_할당하고_가용분이_떨어지면_나머지는_백오더로_남긴다() {
        Product product = productWithStock(0);
        Order older = backorderOf(product, 4);
        Order newer = backorderOf(product, 4);
        product.getInventory().addOnHandQty(5); // 4개짜리 하나만 채울 수 있음
        when(orderRepositoryQuery.findBackordersContaining(List.of(1L))).thenReturn(List.of(older, newer));

        int promoted = backorderAllocator.allocate(List.of(1L));

        assertThat(promoted).isEqualTo(1);
        assertThat(older.getStatus()).isEqualTo(OrderStatus.ORDER);      // FIFO 승자
        assertThat(newer.getStatus()).isEqualTo(OrderStatus.BACKORDERED); // 여전히 대기
        assertThat(product.getInventory().getReservedQty()).isEqualTo(4);
    }

    @Test
    void 여전히_부족하면_아무것도_승격하지_않는다() {
        Product product = productWithStock(0);
        Order backorder = backorderOf(product, 10);
        product.getInventory().addOnHandQty(3); // 부족
        when(orderRepositoryQuery.findBackordersContaining(List.of(1L))).thenReturn(List.of(backorder));

        int promoted = backorderAllocator.allocate(List.of(1L));

        assertThat(promoted).isEqualTo(0);
        assertThat(backorder.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
        assertThat(product.getInventory().getReservedQty()).isEqualTo(0);
    }
}
