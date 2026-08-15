package com.jhg.hgpage.service;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.PaymentAttempt;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.domain.enums.PaymentAttemptStatus;
import com.jhg.hgpage.oms.domain.enums.PaymentStatus;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.repository.PaymentAttemptRepository;
import com.jhg.hgpage.oms.repository.PaymentRepository;
import com.jhg.hgpage.oms.service.OrderCancellationService;
import com.jhg.hgpage.oms.service.RefundService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCancellationServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock PaymentAttemptRepository paymentAttemptRepository;
    @Mock RefundService refundService;

    OrderCancellationService service;

    @BeforeEach
    void setUp() {
        service = new OrderCancellationService(
                orderRepository, paymentRepository, paymentAttemptRepository, refundService);
    }

    @Test
    void PAYMENT_PENDING_미선점은_미결제로_즉시취소한다() {
        Fixture fixture = pendingPayment();
        stubPendingLocks(fixture);

        OrderCancellationService.CancellationResult result = service.request(10L, 1L);

        assertThat(result.paid()).isFalse();
        assertThat(fixture.order.getStatus()).isEqualTo(OrderStatus.CANCEL);
        assertThat(fixture.payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(fixture.attempt.getStatus()).isEqualTo(PaymentAttemptStatus.CANCELLED);
        verifyNoInteractions(refundService);
    }

    @Test
    void PAYMENT_PENDING_처리중과_PAYMENT_REVIEW는_결과가_결정할때까지_null로_남긴다() {
        Fixture processing = pendingPayment();
        processing.attempt.claim(LocalDateTime.now());
        stubPendingLocks(processing);

        service.request(10L, 1L);

        assertThat(processing.order.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(processing.order.getCancellationReleaseRequired()).isNull();
        assertThat(service.claim(10L)).isEmpty();

        Fixture review = paymentState(PaymentStatus.PAYMENT_REVIEW, OrderStatus.PAYMENT_REVIEW);
        stubPaidOrReviewLocks(review);
        service.request(10L, 1L);

        assertThat(review.order.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(review.order.getCancellationReleaseRequired()).isNull();
        verifyNoInteractions(refundService);
    }

    @Test
    void PAYMENT_FAILED는_환불없이_즉시취소한다() {
        Fixture fixture = paymentState(PaymentStatus.PAYMENT_FAILED, OrderStatus.PAYMENT_FAILED);
        stubPaidOrReviewLocks(fixture);

        OrderCancellationService.CancellationResult result = service.request(10L, 1L);

        assertThat(result.paid()).isFalse();
        assertThat(fixture.order.getStatus()).isEqualTo(OrderStatus.CANCEL);
        assertThat(fixture.payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verifyNoInteractions(refundService);
    }

    @Test
    void 할당대기_검토_백오더는_즉시취소하고_전액환불을_한번만_예약한다() {
        for (OrderStatus status : List.of(
                OrderStatus.ALLOCATION_PENDING, OrderStatus.ALLOCATION_REVIEW, OrderStatus.BACKORDERED)) {
            Fixture fixture = paidState(status);
            stubPaidOrReviewLocks(fixture);

            OrderCancellationService.CancellationResult result = service.request(10L, 1L);
            service.request(10L, 1L);

            assertThat(result.paid()).isTrue();
            assertThat(fixture.order.getStatus()).isEqualTo(OrderStatus.CANCEL);
        }
        verify(refundService, times(3)).requestOrderCancellationRefund(10L);
    }

    @Test
    void 할당처리중은_null로_남고_결과가_확정된뒤에만_취소작업이_선점한다() {
        Fixture fixture = paidState(OrderStatus.ALLOCATION_PROCESSING);
        stubPaidOrReviewLocks(fixture);

        service.request(10L, 1L);

        assertThat(fixture.order.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(service.claim(10L)).isEmpty();

        fixture.order.resolveCancellationRelease(false);
        OrderCancellationService.CancellationClaim claim = service.claim(10L).orElseThrow();
        assertThat(claim.releaseRequired()).isFalse();
    }

    @Test
    void ORDER는_해제필요_취소요청만_저장하고_완료트랜잭션에서_환불을_예약한다() {
        Fixture fixture = paidState(OrderStatus.ORDER);
        stubPaidOrReviewLocks(fixture);

        OrderCancellationService.CancellationResult result = service.request(10L, 1L);

        assertThat(result.paid()).isTrue();
        assertThat(fixture.order.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(fixture.order.getCancellationReleaseRequired()).isTrue();
        verify(refundService, never()).requestOrderCancellationRefund(10L);

        OrderCancellationService.CancellationClaim claim = service.claim(10L).orElseThrow();
        assertThat(claim.releaseRequired()).isTrue();
        assertThat(claim.quantities()).isEqualTo(Map.of(7L, 2));
        assertThat(service.complete(10L, claim.attemptNumber())).isTrue();

        assertThat(fixture.order.getStatus()).isEqualTo(OrderStatus.CANCEL);
        verify(refundService).requestOrderCancellationRefund(10L);
    }

    @Test
    void 결제없는_기존ORDER도_해제후_취소되지만_환불은_없다() {
        Fixture fixture = legacyState(OrderStatus.ORDER);
        when(paymentRepository.findByOrderIdForUpdate(10L)).thenReturn(Optional.empty());
        when(orderRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(fixture.order));

        assertThat(service.request(10L, 1L).paid()).isFalse();
        OrderCancellationService.CancellationClaim claim = service.claim(10L).orElseThrow();
        assertThat(service.complete(10L, claim.attemptNumber())).isTrue();

        assertThat(fixture.order.getStatus()).isEqualTo(OrderStatus.CANCEL);
        verifyNoInteractions(refundService);
    }

    @Test
    void 오래된_해제작업A의_결과는_재선점한_작업B를_변경하지_못한다() {
        Fixture fixture = paidState(OrderStatus.ORDER);
        stubPaidOrReviewLocks(fixture);
        service.request(10L, 1L);
        OrderCancellationService.CancellationClaim attemptA = service.claim(10L).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        ReflectionTestUtils.setField(fixture.order, "cancellationProcessingAt", now.minusMinutes(10));
        when(orderRepository.findStaleCancellationOrderIds(now.minusMinutes(5))).thenReturn(List.of(10L));

        service.recoverStaleCancellations(now.minusMinutes(5));
        OrderCancellationService.CancellationClaim attemptB = service.claim(10L).orElseThrow();

        assertThat(service.complete(10L, attemptA.attemptNumber())).isFalse();
        assertThat(fixture.order.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(service.complete(10L, attemptB.attemptNumber())).isTrue();
        verify(refundService).requestOrderCancellationRefund(10L);
    }

    @Test
    void 출고후_취소는_거절하고_타인주문은_404로_숨긴다() {
        Fixture shipped = paidState(OrderStatus.ORDER);
        shipped.order.ship();
        stubPaidOrReviewLocks(shipped);

        assertThatThrownBy(() -> service.request(10L, 1L))
                .isInstanceOf(IllegalStateException.class);

        Fixture delivered = paidState(OrderStatus.ORDER);
        delivered.order.ship();
        delivered.order.deliver();
        stubPaidOrReviewLocks(delivered);

        assertThatThrownBy(() -> service.request(10L, 1L))
                .isInstanceOf(IllegalStateException.class);

        Fixture ownedByOther = paidState(OrderStatus.ORDER);
        stubPaidOrReviewLocks(ownedByOther);
        assertThatThrownBy(() -> service.request(10L, 2L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    private void stubPendingLocks(Fixture fixture) {
        when(paymentAttemptRepository.findFirstByPaymentOrderIdAndStatusInOrderByIdDesc(
                10L, List.of(PaymentAttemptStatus.PENDING, PaymentAttemptStatus.PROCESSING)))
                .thenReturn(Optional.of(fixture.attempt));
        when(paymentRepository.findByOrderIdForUpdate(10L)).thenReturn(Optional.of(fixture.payment));
        when(orderRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(fixture.order));
    }

    private void stubPaidOrReviewLocks(Fixture fixture) {
        when(paymentRepository.findByOrderIdForUpdate(10L)).thenReturn(Optional.of(fixture.payment));
        when(orderRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(fixture.order));
    }

    private Fixture pendingPayment() {
        Order order = order();
        order.markPaymentPending();
        Payment payment = payment(order);
        PaymentAttempt attempt = PaymentAttempt.create(payment, UUID.randomUUID());
        ReflectionTestUtils.setField(attempt, "id", 30L);
        return new Fixture(order, payment, attempt);
    }

    private Fixture paymentState(PaymentStatus paymentStatus, OrderStatus orderStatus) {
        Fixture fixture = pendingPayment();
        if (paymentStatus == PaymentStatus.PAYMENT_FAILED) {
            fixture.payment.markPaymentFailed();
            fixture.order.markPaymentFailed();
        } else {
            fixture.payment.markPaymentReview();
            fixture.order.markPaymentReview();
        }
        assertThat(fixture.order.getStatus()).isEqualTo(orderStatus);
        return fixture;
    }

    private Fixture paidState(OrderStatus status) {
        Order order = order();
        order.markPaymentPending();
        Payment payment = payment(order);
        payment.markPaid(LocalDateTime.now());
        switch (status) {
            case ALLOCATION_PENDING -> order.markAllocationPending();
            case ALLOCATION_PROCESSING -> {
                order.markAllocationPending();
                order.claimAllocation(LocalDateTime.now());
            }
            case ALLOCATION_REVIEW -> {
                order.markAllocationPending();
                order.claimAllocation(LocalDateTime.now());
                order.markAllocationReview("WMS_400");
            }
            case BACKORDERED -> order.markBackordered();
            case ORDER -> order.markOrdered();
            default -> throw new IllegalArgumentException("unsupported state");
        }
        return new Fixture(order, payment, null);
    }

    private Fixture legacyState(OrderStatus status) {
        Order order = order();
        if (status == OrderStatus.BACKORDERED) {
            order.markBackordered();
        } else {
            order.markOrdered();
        }
        return new Fixture(order, null, null);
    }

    private Payment payment(Order order) {
        Payment payment = Payment.create(order, order.getTotalPrice());
        ReflectionTestUtils.setField(payment, "id", 20L);
        return payment;
    }

    private Order order() {
        Product product = new Product();
        product.setId(7L);
        product.setName("상품");
        product.setPrice(10_000);
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        ReflectionTestUtils.setField(member, "id", 1L);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery,
                OrderItem.createOrderItem(product, product.getPrice(), 2));
        ReflectionTestUtils.setField(order, "id", 10L);
        return order;
    }

    private record Fixture(Order order, Payment payment, PaymentAttempt attempt) {
    }
}
