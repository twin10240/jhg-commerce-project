package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.service.CartService;
import com.jhg.hgpage.oms.service.MemberService;
import com.jhg.hgpage.oms.service.OrderService;
import com.jhg.hgpage.oms.service.OrderCancellationService;
import com.jhg.hgpage.contract.InventoryPort;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.dto.OrderDetailDto;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import com.jhg.hgpage.oms.repository.CustomerReturnRepository;
import com.jhg.hgpage.catalog.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.List;

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
    @Mock CustomerReturnRepository customerReturnRepository;
    @Mock OrderCancellationService cancellationService;
    @Mock CartService cartService;
    @Mock InventoryPort inventoryPort;
    @InjectMocks OrderService orderService;

    private Product product;

    /** memberId가 1L인 회원의 주문(상품 2개, 단가 10000, ORDER 상태) */
    private Order orderOwnedBy(long memberId) {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        ReflectionTestUtils.setField(member, "id", memberId);

        product = new Product();
        product.setId(7L);
        product.setName("테스트상품");
        product.setPrice(10000);

        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        OrderItem orderItem = OrderItem.createOrderItem(product, product.getPrice(), 2);
        ReflectionTestUtils.setField(orderItem, "id", 20L);
        Order order = Order.createOrder(member, delivery, orderItem);
        order.markOrdered(); // ORDER 상태(예약 성공)로 둔다
        ReflectionTestUtils.setField(order, "id", 10L);
        return order;
    }

    @Test
    void 본인_주문_상세를_DTO로_반환한다() {
        Order order = orderOwnedBy(1L);
        Payment payment = Payment.create(order, 20_000);
        payment.markPaid(java.time.LocalDateTime.now());
        when(orderRepositoryQuery.findDetailById(10L)).thenReturn(Optional.of(order));
        when(orderRepositoryQuery.findPaymentByOrderId(10L)).thenReturn(Optional.of(payment));

        OrderDetailDto detail = orderService.findOrderDetail(10L, 1L);

        assertThat(detail.getStatus()).isEqualTo(OrderStatus.ORDER);
        assertThat(detail.getDeliveryStatus()).isEqualTo(DeliveryStatus.READY);
        assertThat(detail.getItems()).hasSize(1);
        assertThat(detail.getItems().get(0).getProductName()).isEqualTo("테스트상품");
        assertThat(detail.getItems().get(0).getOrderItemId()).isEqualTo(20L);
        assertThat(detail.getItems().get(0).getProductId()).isEqualTo(7L);
        assertThat(detail.getItems().get(0).getTotalPrice()).isEqualTo(20000);
        assertThat(detail.getTotalPrice()).isEqualTo(20000);
        assertThat(detail.isCancelable()).isTrue();
        assertThat(detail.getPaymentStatusLabel()).isEqualTo("결제 완료");
        assertThat(detail.getPaidAmount()).isEqualTo(20_000);
    }

    @Test
    void 주문목록의_결제정보를_주문별조회없이_한번에_가져온다() {
        Order first = orderOwnedBy(1L);
        ReflectionTestUtils.setField(first, "id", 10L);
        Order second = orderOwnedBy(1L);
        ReflectionTestUtils.setField(second, "id", 11L);
        Payment payment = Payment.create(first, 20_000);
        payment.markPaid(java.time.LocalDateTime.now());
        when(orderRepositoryQuery.findOrders(1L)).thenReturn(List.of(first, second));
        when(orderRepositoryQuery.findPaymentsByOrderIds(List.of(10L, 11L))).thenReturn(List.of(payment));

        var orders = orderService.findOrders(1L);

        assertThat(orders).extracting("paymentStatusLabel")
                .containsExactly("결제 완료", "결제 이력 없음");
        verify(orderRepositoryQuery).findPaymentsByOrderIds(List.of(10L, 11L));
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
    void 기존_주문서비스_취소API도_복구가능한_취소서비스로_위임한다() {
        orderService.cancelOrder(10L, 1L);

        verify(cancellationService).request(10L, 1L);
    }

    @Test
    void 기존_주문서비스_취소API는_쓰기트랜잭션을_선언한다() throws Exception {
        Transactional transactional = OrderService.class
                .getMethod("cancelOrder", Long.class, Long.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isFalse();
    }

    @Test
    void 타인_주문_취소는_존재를_숨기고_거부한다() {
        org.mockito.Mockito.doThrow(new EntityNotFoundException("Order", 10L))
                .when(cancellationService).request(10L, 1L);

        assertThatThrownBy(() -> orderService.cancelOrder(10L, 1L))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
