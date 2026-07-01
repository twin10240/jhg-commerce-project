package com.jhg.hgpage.service;

import com.jhg.hgpage.contract.InventoryPort;
import com.jhg.hgpage.oms.service.BackorderAllocator;
import com.jhg.hgpage.oms.service.OrderAllocationService;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 입고/재고증가 시 백오더 자동 할당 — 오래된 주문(FIFO)부터 가용분을 배정해
 * 가능해진 주문을 ORDER로 승격한다.
 *
 * <p>재고 예약은 InventoryPort(WMS HTTP)에 위임되므로 포트를 목으로 둔다.
 */
@ExtendWith(MockitoExtension.class)
class BackorderAllocatorTest {

    @Mock OrderRepositoryQuery orderRepositoryQuery;
    @Mock InventoryPort inventoryPort;

    private BackorderAllocator backorderAllocator;

    @BeforeEach
    void setUp() {
        OrderAllocationService orderAllocationService = new OrderAllocationService(inventoryPort);
        backorderAllocator = new BackorderAllocator(orderRepositoryQuery, orderAllocationService);
    }

    private Product product() {
        Product product = new Product();
        product.setId(1L);
        product.setName("상품");
        product.setPrice(10000);
        return product;
    }

    private Order backorderOf(Product product, int quantity) {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery,
                OrderItem.createOrderItem(product, product.getPrice(), quantity));
        order.markBackordered(); // 접수 당시 가용분 부족으로 백오더 접수된 상태
        return order;
    }

    @Test
    void 가용해진_백오더를_ORDER로_승격하고_예약한다() {
        Product product = product();
        Order backorder = backorderOf(product, 3);
        when(orderRepositoryQuery.findBackordersContaining(List.of(1L))).thenReturn(List.of(backorder));
        when(inventoryPort.reserveAll(any(), any())).thenReturn(true);

        int promoted = backorderAllocator.allocate(List.of(1L));

        assertThat(promoted).isEqualTo(1);
        assertThat(backorder.getStatus()).isEqualTo(OrderStatus.ORDER);
    }

    @Test
    void 오래된_주문부터_할당하고_가용분이_떨어지면_나머지는_백오더로_남긴다() {
        Product product = product();
        Order older = backorderOf(product, 4);
        Order newer = backorderOf(product, 4);
        when(orderRepositoryQuery.findBackordersContaining(List.of(1L))).thenReturn(List.of(older, newer));
        when(inventoryPort.reserveAll(any(), any())).thenReturn(true, false);

        int promoted = backorderAllocator.allocate(List.of(1L));

        assertThat(promoted).isEqualTo(1);
        assertThat(older.getStatus()).isEqualTo(OrderStatus.ORDER);       // FIFO 승자
        assertThat(newer.getStatus()).isEqualTo(OrderStatus.BACKORDERED); // 여전히 대기
    }

    @Test
    void 여전히_부족하면_아무것도_승격하지_않는다() {
        Product product = product();
        Order backorder = backorderOf(product, 10);
        when(orderRepositoryQuery.findBackordersContaining(List.of(1L))).thenReturn(List.of(backorder));
        when(inventoryPort.reserveAll(any(), any())).thenReturn(false);

        int promoted = backorderAllocator.allocate(List.of(1L));

        assertThat(promoted).isEqualTo(0);
        assertThat(backorder.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    }
}
