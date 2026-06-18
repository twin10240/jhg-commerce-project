package com.jhg.hgpage.service;

import com.jhg.hgpage.contract.InventoryPort;

import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Delivery;
import com.jhg.hgpage.domain.Inventory;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Order;
import com.jhg.hgpage.domain.OrderItem;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.domain.enums.OrderStatus;
import com.jhg.hgpage.repository.OrderRepository;
import com.jhg.hgpage.repository.OrderRepositoryQuery;
import com.jhg.hgpage.catalog.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 주문 취소 시 예약(reserved)이 풀려 가용분이 늘어나면,
 * 그 상품을 기다리던 백오더를 재할당(승격)하도록 트리거해야 한다.
 * BACKORDERED 주문 취소는 풀릴 예약이 없으므로 트리거하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceCancelTest {

    @Mock MemberService memberService;
    @Mock ProductRepository productRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderRepositoryQuery orderRepositoryQuery;
    @Mock CartService cartService;
    @Mock BackorderAllocator backorderAllocator;
    @Mock InventoryPort inventoryPort;
    @InjectMocks OrderService orderService;

    private static final Address ADDRESS = new Address("서울", "관악구", "500");

    private Member memberWithId(long memberId) {
        Member member = Member.createUser("테스터", "010-0000-0000", ADDRESS);
        ReflectionTestUtils.setField(member, "id", memberId);
        return member;
    }

    private Product productWithStock(long id, int stock) {
        Product product = new Product();
        product.setId(id);
        product.setName("상품" + id);
        product.setPrice(10000);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(stock);
        product.setInventory(inventory);
        return product;
    }

    private Order orderOf(Member member, OrderItem... items) {
        Delivery delivery = new Delivery();
        delivery.setAddress(ADDRESS);
        return Order.createOrder(member, delivery, items);
    }

    @Test
    void ORDER_취소로_예약이_풀리면_해당_상품의_백오더_재할당을_트리거한다() {
        Product product = productWithStock(7L, 3);
        Order order = orderOf(memberWithId(1L), OrderItem.createOrderItem(product, 10000, 3));
        order.markOrdered(); // ORDER 상태(예약 성공)
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER);
        when(orderRepositoryQuery.findDetailById(10L)).thenReturn(Optional.of(order));

        orderService.cancelOrder(10L, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL);
        // 예약 해제는 도메인이 아니라 InventoryPort(WMS)에 위임한다
        verify(inventoryPort).releaseAll(Map.of(7L, 3));
        verify(backorderAllocator).allocate(List.of(7L));
    }

    @Test
    void BACKORDERED_취소는_풀릴_예약이_없어_재할당을_트리거하지_않는다() {
        Product product = productWithStock(8L, 0);
        Order order = orderOf(memberWithId(1L), OrderItem.createOrderItem(product, 10000, 2));
        order.markBackordered(); // 백오더 접수 상태(예약 없음)
        assertThat(order.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
        when(orderRepositoryQuery.findDetailById(10L)).thenReturn(Optional.of(order));

        orderService.cancelOrder(10L, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL);
        verifyNoInteractions(backorderAllocator);
        // 풀릴 예약이 없으므로 재고 해제도 일어나지 않는다
        verifyNoInteractions(inventoryPort);
    }

    @Test
    void 여러_상품_주문_취소는_모든_상품_id로_재할당을_트리거한다() {
        Product p1 = productWithStock(7L, 5);
        Product p2 = productWithStock(8L, 5);
        Order order = orderOf(memberWithId(1L),
                OrderItem.createOrderItem(p1, 10000, 2),
                OrderItem.createOrderItem(p2, 10000, 1));
        order.markOrdered(); // 둘 다 예약 성공 → ORDER
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER);
        when(orderRepositoryQuery.findDetailById(10L)).thenReturn(Optional.of(order));

        orderService.cancelOrder(10L, 1L);

        verify(inventoryPort).releaseAll(Map.of(7L, 2, 8L, 1));
        verify(backorderAllocator).allocate(List.of(7L, 8L));
    }
}
