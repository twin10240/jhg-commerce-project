package com.jhg.hgpage.service;

import com.jhg.hgpage.contract.InventoryPort;

import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Delivery;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Order;
import com.jhg.hgpage.domain.OrderItem;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.domain.enums.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 주문 할당 정책 — 전 라인 예약 시도 결과(InventoryPort.reserveAll)에 따라 ORDER/BACKORDERED로 표시한다.
 * 예약 자체(가용성·원자성)는 InventoryPort 구현(InventoryServiceTest)이 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class OrderAllocationServiceTest {

    @Mock InventoryPort inventoryPort;
    @InjectMocks OrderAllocationService orderAllocationService;

    private Product product(long id) {
        Product product = new Product();
        product.setId(id);
        product.setName("상품" + id);
        product.setPrice(10000);
        return product;
    }

    private Order orderOf(OrderItem... items) {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        return Order.createOrder(member, delivery, items);
    }

    @Test
    void 전_라인_예약에_성공하면_ORDER로_표시한다() {
        Order order = orderOf(OrderItem.createOrderItem(product(1L), 10000, 2));
        when(inventoryPort.reserveAll(Map.of(1L, 2))).thenReturn(true);

        orderAllocationService.allocate(order);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER);
        verify(inventoryPort).reserveAll(Map.of(1L, 2));
    }

    @Test
    void 예약에_실패하면_BACKORDERED로_표시한다() {
        Order order = orderOf(OrderItem.createOrderItem(product(1L), 10000, 5));
        when(inventoryPort.reserveAll(Map.of(1L, 5))).thenReturn(false);

        orderAllocationService.allocate(order);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    }
}
