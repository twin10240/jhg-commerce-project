package com.jhg.hgpage.service;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import com.jhg.hgpage.oms.service.OrderAllocationService;
import com.jhg.hgpage.oms.service.RetrySchedule;
import com.jhg.hgpage.realtime.outbox.NotificationEventType;
import com.jhg.hgpage.realtime.outbox.NotificationEventWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderAllocationServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderRepositoryQuery orderRepositoryQuery;
    @Mock NotificationEventWriter eventWriter;

    OrderAllocationService service;

    @BeforeEach
    void setUp() {
        service = new OrderAllocationService(orderRepository, orderRepositoryQuery, new RetrySchedule(), eventWriter);
    }

    @Test
    void 도래한_유료주문을_선점하고_수량명령을_반환한다() {
        Order order = pendingOrder();
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        Optional<OrderAllocationService.AllocationCommand> command = service.claim(100L);

        assertThat(command).contains(new OrderAllocationService.AllocationCommand(1, Map.of(1L, 2)));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATION_PROCESSING);
        assertThat(order.getAllocationAttemptCount()).isEqualTo(1);
    }

    @Test
    void 예약성공은_ORDER_명시적부족은_BACKORDERED로_완료한다() {
        Order reserved = processingOrder();
        Order shortage = processingOrder();
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(reserved), Optional.of(shortage));

        service.complete(100L, 1, true);
        service.complete(100L, 1, false);

        assertThat(reserved.getStatus()).isEqualTo(OrderStatus.ORDER);
        assertThat(shortage.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
        verify(eventWriter).append(NotificationEventType.STOCK_ALLOCATED, 1L,
                "ORDER", "100", Map.of("orderId", 100L));
        verify(eventWriter).append(NotificationEventType.ORDER_BACKORDERED, 1L,
                "ORDER", "100", Map.of("orderId", 100L));
    }

    @Test
    void 일시실패는_1분뒤_같은주문_재시도를_예약한다() {
        Order order = processingOrder();
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        LocalDateTime before = LocalDateTime.now().plusSeconds(59);

        service.retryOrReview(100L, 1, "WMS_UNAVAILABLE");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATION_PENDING);
        assertThat(order.getAllocationFailureCode()).isEqualTo("WMS_UNAVAILABLE");
        assertThat(order.getNextAllocationAttemptAt()).isAfter(before);
    }

    @Test
    void 영구실패와_다섯번째_일시실패는_할당검토로_보낸다() {
        Order permanent = processingOrder();
        Order exhausted = processingOrder(5);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(permanent), Optional.of(exhausted));

        service.manualReview(100L, 1, "WMS_400");
        service.retryOrReview(100L, 5, "WMS_UNAVAILABLE");

        assertThat(permanent.getStatus()).isEqualTo(OrderStatus.ALLOCATION_REVIEW);
        assertThat(permanent.getAllocationFailureCode()).isEqualTo("WMS_400");
        assertThat(exhausted.getStatus()).isEqualTo(OrderStatus.ALLOCATION_REVIEW);
    }

    @Test
    void 오래된_처리중_할당은_즉시_재시도상태로_복구한다() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 15, 12, 0);
        Order order = pendingOrder();
        order.claimAllocation(now.minusMinutes(10));
        when(orderRepositoryQuery.findStaleAllocationOrderIds(now.minusMinutes(5))).thenReturn(List.of(100L));
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        service.recoverStaleAllocations(now.minusMinutes(5), now);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATION_PENDING);
        assertThat(order.getNextAllocationAttemptAt()).isEqualTo(now);
        assertThat(order.getAllocationFailureCode()).isEqualTo("STALE_PROCESSING");
    }

    @Test
    void 처리중_취소후_예약결과가_도착하면_해제필요여부만_확정한다() {
        Order reserved = cancelledProcessingOrder();
        Order shortage = cancelledProcessingOrder();
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(reserved), Optional.of(shortage));

        service.complete(100L, 1, true);
        service.complete(100L, 1, false);

        assertThat(reserved.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(reserved.getCancellationReleaseRequired()).isTrue();
        assertThat(shortage.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(shortage.getCancellationReleaseRequired()).isFalse();
    }

    @Test
    void 처리중_취소후_통신결과불명은_null을_유지하고_같은주문을_재시도한다() {
        Order order = cancelledProcessingOrder();
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order), Optional.of(order));

        service.retryOrReview(100L, 1, "WMS_UNAVAILABLE");
        ReflectionTestUtils.setField(order, "nextAllocationAttemptAt", LocalDateTime.now().minusSeconds(1));
        Optional<OrderAllocationService.AllocationCommand> retried = service.claim(100L);

        assertThat(retried).isPresent();
        assertThat(retried.orElseThrow().attemptNumber()).isEqualTo(2);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(order.getCancellationReleaseRequired()).isNull();
        assertThat(order.getAllocationAttemptCount()).isEqualTo(2);
    }

    @Test
    void 취소중_다섯번째_결과불명은_취소대기_수동검토형태로_남긴다() {
        Order order = processingOrder(5);
        order.requestCancellation(null, LocalDateTime.now());
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        service.retryOrReview(100L, 5, "WMS_UNAVAILABLE");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(order.getCancellationReleaseRequired()).isNull();
        assertThat(order.getAllocationAttemptCount()).isEqualTo(5);
        assertThat(order.getAllocationFailureCode()).isEqualTo("WMS_UNAVAILABLE");
        assertThat(order.getNextAllocationAttemptAt()).isNull();
        assertThat(order.getAllocationProcessingAt()).isNull();
    }

    @Test
    void 취소중_수동검토_셀렉터와_재큐는_같은주문을_다시_처리가능하게_만든다() {
        Order order = processingOrder(5);
        order.requestCancellation(null, LocalDateTime.now());
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        service.retryOrReview(100L, 5, "WMS_UNAVAILABLE");
        when(orderRepositoryQuery.findCancellationAllocationReviewOrderIds()).thenReturn(List.of(100L));

        assertThat(service.findCancellationAllocationReviewOrderIds()).containsExactly(100L);
        assertThat(service.requeueCancellationAllocation(100L)).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(order.getCancellationReleaseRequired()).isNull();
        assertThat(order.getNextAllocationAttemptAt()).isNotNull();
    }

    @Test
    void 일반_할당검토만_같은주문과_시도횟수로_재큐한다() {
        Order order = processingOrder(5);
        order.markAllocationReview("WMS_UNAVAILABLE");
        int attemptCount = order.getAllocationAttemptCount();
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        assertThat(service.requeueAllocationReview(100L)).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATION_PENDING);
        assertThat(order.getAllocationAttemptCount()).isEqualTo(attemptCount);
        assertThat(order.getNextAllocationAttemptAt()).isNotNull();
        assertThat(service.requeueAllocationReview(100L)).isFalse();
    }

    @Test
    void 오래된_작업A의_결과는_재선점한_작업B를_변경하지_못한다() {
        Order order = pendingOrder();
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        LocalDateTime now = LocalDateTime.now();
        when(orderRepositoryQuery.findStaleAllocationOrderIds(now.minusMinutes(5))).thenReturn(List.of(100L));

        OrderAllocationService.AllocationCommand attemptA = service.claim(100L).orElseThrow();
        ReflectionTestUtils.setField(order, "allocationProcessingAt", now.minusMinutes(10));
        service.recoverStaleAllocations(now.minusMinutes(5), now.minusSeconds(1));
        OrderAllocationService.AllocationCommand attemptB = service.claim(100L).orElseThrow();
        LocalDateTime attemptBProcessingAt = order.getAllocationProcessingAt();

        service.complete(100L, attemptA.attemptNumber(), true);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATION_PROCESSING);
        assertThat(order.getAllocationAttemptCount()).isEqualTo(attemptB.attemptNumber());
        assertThat(order.getAllocationProcessingAt()).isEqualTo(attemptBProcessingAt);

        service.complete(100L, attemptB.attemptNumber(), true);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER);
        assertThat(order.getAllocationProcessingAt()).isNull();
    }

    private Order pendingOrder() {
        Order order = order();
        order.markPaymentPending();
        order.markAllocationPending();
        return order;
    }

    private Order processingOrder() {
        return processingOrder(1);
    }

    private Order processingOrder(int attempts) {
        Order order = pendingOrder();
        for (int i = 1; i < attempts; i++) {
            order.claimAllocation(LocalDateTime.now());
            order.retryAllocation(LocalDateTime.now(), "WMS_UNAVAILABLE");
        }
        order.claimAllocation(LocalDateTime.now());
        return order;
    }

    private Order cancelledProcessingOrder() {
        Order order = processingOrder();
        order.requestCancellation(null, LocalDateTime.now());
        return order;
    }

    private Order order() {
        Product product = new Product();
        product.setId(1L);
        product.setName("상품");
        product.setPrice(10_000);
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        ReflectionTestUtils.setField(member, "id", 1L);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery, OrderItem.createOrderItem(product, 10_000, 2));
        ReflectionTestUtils.setField(order, "id", 100L);
        return order;
    }
}
