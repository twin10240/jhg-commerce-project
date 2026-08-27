package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.service.CartService;
import com.jhg.hgpage.oms.service.MemberService;
import com.jhg.hgpage.oms.service.OrderService;
import com.jhg.hgpage.contract.InventoryPort;
import com.jhg.hgpage.contract.InventoryQueryPort;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.dto.AdminOrderDto;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceAdminTest {

    @Mock MemberService memberService;
    @Mock ProductRepository productRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderRepositoryQuery orderRepositoryQuery;
    @Mock CustomerReturnRepository customerReturnRepository;
    @Mock CartService cartService;
    @Mock InventoryPort inventoryPort;
    @Mock InventoryQueryPort inventoryQueryPort;
    @InjectMocks OrderService orderService;

    private Order newOrder(String memberName) {
        Member member = Member.createUser(memberName, "010-0000-0000", new Address("서울", "관악구", "500"));
        Product product = new Product();
        product.setId(1L);
        product.setName("테스트상품");
        product.setPrice(10000);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery, OrderItem.createOrderItem(product, product.getPrice(), 2));
        order.markOrdered(); // 예약 성공 상태(ORDER)로 둔다 — 예약 자체는 서비스/포트가 담당
        return order;
    }

    @Test
    void 전체_주문을_관리자용_DTO로_매핑한다() {
        Order active = newOrder("회원A");
        Order canceled = newOrder("회원B");
        canceled.cancel();
        when(orderRepositoryQuery.findAllForAdmin()).thenReturn(List.of(active, canceled));

        List<AdminOrderDto> result = orderService.findAllForAdmin();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getMemberName()).isEqualTo("회원A");
        assertThat(result.get(0).getTotalPrice()).isEqualTo(20000);
        assertThat(result.get(0).isShippable()).isTrue();
        assertThat(result.get(0).isDeliverable()).isFalse();
        assertThat(result.get(1).getStatus()).isEqualTo(OrderStatus.CANCEL);
        assertThat(result.get(1).isShippable()).isFalse();
        assertThat(result.get(1).isDeliverable()).isFalse();
    }

    @Test
    void 관리자주문도_결제를_일괄조회하고_두_할당검토경로를_구분한다() {
        Order normalReview = newOrder("회원A");
        ReflectionTestUtils.setField(normalReview, "id", 10L);
        ReflectionTestUtils.setField(normalReview, "status", OrderStatus.ALLOCATION_REVIEW);
        Order cancellationReview = newOrder("회원B");
        ReflectionTestUtils.setField(cancellationReview, "id", 11L);
        cancellationReview.requestCancellation(null, java.time.LocalDateTime.now());
        Payment payment = Payment.create(normalReview, normalReview.getTotalPrice());
        payment.markPaid(java.time.LocalDateTime.now());
        when(orderRepositoryQuery.findAllForAdmin()).thenReturn(List.of(normalReview, cancellationReview));
        when(orderRepositoryQuery.findPaymentsByOrderIds(List.of(10L, 11L))).thenReturn(List.of(payment));
        when(orderRepositoryQuery.findCancellationAllocationReviewOrderIds()).thenReturn(List.of(11L));

        List<AdminOrderDto> result = orderService.findAllForAdmin();

        assertThat(result.get(0).isAllocationRetryable()).isTrue();
        assertThat(result.get(0).getOrderStatusLabel()).isEqualTo("재고 확인 지연");
        assertThat(result.get(0).getPaymentStatusLabel()).isEqualTo("결제 완료");
        assertThat(result.get(1).isCancellationAllocationRetryable()).isTrue();
        verify(orderRepositoryQuery).findPaymentsByOrderIds(List.of(10L, 11L));
        verify(orderRepositoryQuery).findCancellationAllocationReviewOrderIds();
    }

    @Test
    void 백오더_상품과_입고필요_여부를_관리자용_DTO로_매핑한다() throws Exception {
        Member member = Member.createUser("회원A", "010-0000-0000", new Address("서울", "관악구", "500"));
        Product available = new Product();
        available.setId(1L);
        available.setName("재고충분상품");
        available.setPrice(10000);
        Product shortage = new Product();
        shortage.setId(3L);
        shortage.setName("재고부족상품");
        shortage.setPrice(12000);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery,
                OrderItem.createOrderItem(available, available.getPrice(), 2),
                OrderItem.createOrderItem(shortage, shortage.getPrice(), 3));
        order.markBackordered();
        when(orderRepositoryQuery.findAllForAdmin()).thenReturn(List.of(order));
        when(inventoryQueryPort.availableByProductIds(List.of(1L, 3L))).thenReturn(Map.of(1L, 2, 3L, 1));

        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .writeValueAsString(orderService.findAllForAdmin().get(0));

        assertThat(json)
                .contains("\"productName\":\"재고충분상품\"", "\"quantity\":2", "\"inboundRequired\":false")
                .contains("\"productName\":\"재고부족상품\"", "\"quantity\":3", "\"inboundRequired\":true");
        verify(inventoryQueryPort).availableByProductIds(List.of(1L, 3L));
    }

    @Test
    void 출고_처리하면_배송상태가_SHIPPED가_되고_재고_출고를_포트에_위임한다() {
        Order order = newOrder("회원A"); // 상품1, 수량 2
        ReflectionTestUtils.setField(order, "id", 10L);

        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(inventoryPort.shipAll(10L, Map.of(1L, 2))).thenReturn(
                new InventoryPort.ShipmentResult(10L, "MOCK", "테스트택배", "MOCK-10", Instant.parse("2026-08-27T06:30:00.123456Z")));

        orderService.shipOrder(10L);

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.SHIPPED);
        assertThat(order.getDelivery().getTrackingNumber()).isEqualTo("MOCK-10");
        assertThat(order.getDelivery().getShipmentIssuedAt()).isEqualTo(Instant.parse("2026-08-27T06:30:00.123456Z"));
        // 실물 차감은 도메인이 아니라 InventoryPort(WMS)에 위임한다
        verify(inventoryPort).shipAll(10L, Map.of(1L, 2));
    }

    @Test
    void 배송_완료하면_WMS를_호출하지_않는다() {
        Order order = newOrder("회원A");
        ReflectionTestUtils.setField(order, "id", 10L);
        order.ship();
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        orderService.deliverOrder(10L);

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        verifyNoInteractions(inventoryPort);
    }

    @Test
    void 없는_주문의_출고처리는_EntityNotFoundException을_던진다() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.shipOrder(99L))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
