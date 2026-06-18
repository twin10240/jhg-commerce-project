package com.jhg.hgpage.service;

import com.jhg.hgpage.contract.InventoryPort;

import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Delivery;
import com.jhg.hgpage.domain.Inventory;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Order;
import com.jhg.hgpage.domain.OrderItem;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.domain.dto.view.OrderDetailDto;
import com.jhg.hgpage.domain.enums.DeliveryStatus;
import com.jhg.hgpage.domain.enums.OrderStatus;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.repository.OrderRepository;
import com.jhg.hgpage.repository.OrderRepositoryQuery;
import com.jhg.hgpage.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceDetailTest {

    @Mock MemberService memberService;
    @Mock ProductRepository productRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderRepositoryQuery orderRepositoryQuery;
    @Mock CartService cartService;
    @Mock BackorderAllocator backorderAllocator;
    @Mock InventoryPort inventoryPort;
    @InjectMocks OrderService orderService;

    private Product product;

    /** memberId가 1L인 회원의 주문(상품 2개, 단가 10000, 재고 10→8) */
    private Order orderOwnedBy(long memberId) {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        ReflectionTestUtils.setField(member, "id", memberId);

        product = new Product();
        product.setId(7L);
        product.setName("테스트상품");
        product.setPrice(10000);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(10);
        product.setInventory(inventory);

        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery, OrderItem.createOrderItem(product, product.getPrice(), 2));
        order.markOrdered(); // ORDER 상태(예약 성공)로 둔다
        return order;
    }

    @Test
    void 본인_주문_상세를_DTO로_반환한다() {
        when(orderRepositoryQuery.findDetailById(10L)).thenReturn(Optional.of(orderOwnedBy(1L)));

        OrderDetailDto detail = orderService.findOrderDetail(10L, 1L);

        assertThat(detail.getStatus()).isEqualTo(OrderStatus.ORDER);
        assertThat(detail.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
        assertThat(detail.getItems()).hasSize(1);
        assertThat(detail.getItems().get(0).getProductName()).isEqualTo("테스트상품");
        assertThat(detail.getItems().get(0).getTotalPrice()).isEqualTo(20000);
        assertThat(detail.getTotalPrice()).isEqualTo(20000);
        assertThat(detail.isCancelable()).isTrue();
    }

    @Test
    void 타인_주문_상세는_존재를_숨기고_EntityNotFoundException을_던진다() {
        when(orderRepositoryQuery.findDetailById(10L)).thenReturn(Optional.of(orderOwnedBy(2L)));

        assertThatThrownBy(() -> orderService.findOrderDetail(10L, 1L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void 없는_주문_상세는_EntityNotFoundException을_던진다() {
        when(orderRepositoryQuery.findDetailById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.findOrderDetail(99L, 1L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void 백오더_주문의_상세는_입고대기_상태고_취소는_가능하다() {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        ReflectionTestUtils.setField(member, "id", 1L);
        Product scarce = new Product();
        scarce.setId(8L);
        scarce.setName("부족상품");
        scarce.setPrice(10000);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(0);
        scarce.setInventory(inventory);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery, OrderItem.createOrderItem(scarce, 10000, 2));
        order.markBackordered(); // 가용분 부족으로 백오더 접수된 상태
        when(orderRepositoryQuery.findDetailById(10L)).thenReturn(Optional.of(order));

        OrderDetailDto detail = orderService.findOrderDetail(10L, 1L);

        assertThat(detail.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
        assertThat(detail.isCancelable()).isTrue(); // 백오더는 예약이 없어 자유롭게 취소 가능
    }

    @Test
    void 취소된_주문의_상세는_취소불가로_표시된다() {
        Order order = orderOwnedBy(1L);
        order.cancel();
        when(orderRepositoryQuery.findDetailById(10L)).thenReturn(Optional.of(order));

        OrderDetailDto detail = orderService.findOrderDetail(10L, 1L);

        assertThat(detail.getStatus()).isEqualTo(OrderStatus.CANCEL);
        assertThat(detail.isCancelable()).isFalse();
    }

    @Test
    void 본인_주문을_취소하면_CANCEL이_되고_예약_해제를_포트에_위임한다() {
        Order order = orderOwnedBy(1L);
        when(orderRepositoryQuery.findDetailById(10L)).thenReturn(Optional.of(order));

        orderService.cancelOrder(10L, 1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL);
        verify(inventoryPort).releaseAll(Map.of(7L, 2)); // 예약 해제는 WMS 포트에 위임
    }

    @Test
    void 타인_주문_취소는_존재를_숨기고_거부한다() {
        Order order = orderOwnedBy(2L);
        when(orderRepositoryQuery.findDetailById(10L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancelOrder(10L, 1L))
                .isInstanceOf(EntityNotFoundException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER); // 취소되지 않음
    }
}
