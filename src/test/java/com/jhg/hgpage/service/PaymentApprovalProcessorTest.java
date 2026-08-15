package com.jhg.hgpage.service;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.contract.PaymentGateway;
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
import com.jhg.hgpage.oms.service.PaymentApprovalProcessor;
import com.jhg.hgpage.oms.service.PaymentService;
import com.jhg.hgpage.oms.service.RetrySchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.jhg.hgpage.contract.PaymentGateway.GatewayOutcome.DECLINED;
import static com.jhg.hgpage.contract.PaymentGateway.GatewayOutcome.RETRYABLE_FAILURE;
import static com.jhg.hgpage.contract.PaymentGateway.GatewayOutcome.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentApprovalProcessorTest {

    @Mock PaymentAttemptRepository paymentAttemptRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock OrderRepository orderRepository;
    @Mock RetrySchedule retrySchedule;
    @Mock PaymentGateway gateway;

    PaymentService paymentService;
    PaymentApprovalProcessor processor;
    Fixture fixture;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentAttemptRepository, paymentRepository, orderRepository, retrySchedule);
        processor = new PaymentApprovalProcessor(paymentService, gateway);
        fixture = fixture();
    }

    private void stubApprovalLocks() {
        when(paymentAttemptRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(fixture.attempt));
        when(paymentRepository.findByOrderIdForUpdate(10L)).thenReturn(Optional.of(fixture.payment));
        when(orderRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(fixture.order));
    }

    @Test
    void 승인성공은_결제완료와_할당대기로_전환한다() {
        stubApprovalLocks();
        when(gateway.approve(any())).thenReturn(new PaymentGateway.ApprovalResult(SUCCESS,
                "MOCK-PAY-1", null, null));

        processor.process(30L);

        assertThat(fixture.attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(fixture.payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(fixture.order.getStatus()).isEqualTo(OrderStatus.ALLOCATION_PENDING);
    }

    @Test
    void 명시적_거절은_결제실패로_전환한다() {
        stubApprovalLocks();
        when(gateway.approve(any())).thenReturn(new PaymentGateway.ApprovalResult(DECLINED,
                null, "DECLINED", "declined"));

        processor.process(30L);

        assertThat(fixture.attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(fixture.payment.getStatus()).isEqualTo(PaymentStatus.PAYMENT_FAILED);
        assertThat(fixture.order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
    }

    @Test
    void 일시실패는_같은_요청키로_다음_승인을_예약한다() {
        stubApprovalLocks();
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(1);
        when(retrySchedule.nextAttemptAt(eq(1), any())).thenReturn(Optional.of(retryAt));
        when(gateway.approve(any())).thenReturn(new PaymentGateway.ApprovalResult(RETRYABLE_FAILURE,
                null, "TIMEOUT", "timeout"));

        processor.process(30L);

        ArgumentCaptor<PaymentGateway.ApprovalCommand> command = ArgumentCaptor.forClass(PaymentGateway.ApprovalCommand.class);
        verify(gateway).approve(command.capture());
        assertThat(command.getValue().requestKey()).isEqualTo(fixture.attempt.getRequestKey());
        assertThat(fixture.attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(fixture.attempt.getNextAttemptAt()).isEqualTo(retryAt);
    }

    @Test
    void 게이트웨이_예외도_영속_재시도상태로_되돌린다() {
        stubApprovalLocks();
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(1);
        when(retrySchedule.nextAttemptAt(eq(1), any())).thenReturn(Optional.of(retryAt));
        when(gateway.approve(any())).thenThrow(new IllegalStateException("connection reset"));

        processor.process(30L);

        assertThat(fixture.attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(fixture.attempt.getFailureCode()).isEqualTo("GATEWAY_ERROR");
        assertThat(fixture.attempt.getNextAttemptAt()).isEqualTo(retryAt);
    }

    @Test
    void 다섯번째_불명확한_실패는_수동확인으로_전환한다() {
        stubApprovalLocks();
        for (int i = 0; i < 4; i++) {
            fixture.attempt.claim(LocalDateTime.now());
            fixture.attempt.retryAt(LocalDateTime.now().minusSeconds(1), "TIMEOUT", "timeout");
        }
        when(retrySchedule.nextAttemptAt(eq(5), any())).thenReturn(Optional.empty());
        when(gateway.approve(any())).thenReturn(new PaymentGateway.ApprovalResult(
                PaymentGateway.GatewayOutcome.UNKNOWN, null, "UNKNOWN", "unknown"));

        processor.process(30L);

        assertThat(fixture.attempt.getStatus()).isEqualTo(PaymentAttemptStatus.MANUAL_REVIEW);
        assertThat(fixture.payment.getStatus()).isEqualTo(PaymentStatus.PAYMENT_REVIEW);
        assertThat(fixture.order.getStatus()).isEqualTo(OrderStatus.PAYMENT_REVIEW);
    }

    @Test
    void 오래된_처리중_시도는_같은_요청키로_복구한다() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minusMinutes(5);
        fixture.attempt.claim(now);
        ReflectionTestUtils.setField(fixture.attempt, "updatedAt", now.minusMinutes(10));
        UUID requestKey = fixture.attempt.getRequestKey();
        when(paymentAttemptRepository.findTop50ByStatusAndUpdatedAtLessThanEqualOrderById(
                PaymentAttemptStatus.PROCESSING, staleBefore))
                .thenReturn(List.of(fixture.attempt));
        when(paymentRepository.findByOrderIdForUpdate(10L)).thenReturn(Optional.of(fixture.payment));
        when(orderRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(fixture.order));

        paymentService.recoverStaleApprovals(staleBefore, now);

        assertThat(fixture.attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(fixture.attempt.getRequestKey()).isEqualTo(requestKey);
    }

    @Test
    void 결제실패_소유자만_새_요청키로_재결제할_수_있다() {
        fixture.attempt.claim(LocalDateTime.now());
        fixture.attempt.fail("DECLINED", "declined", LocalDateTime.now());
        fixture.payment.markPaymentFailed();
        fixture.order.markPaymentFailed();
        when(orderRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(fixture.order));
        when(paymentRepository.findByOrderIdForUpdate(10L)).thenReturn(Optional.of(fixture.payment));
        when(paymentAttemptRepository.save(any())).thenAnswer(invocation -> {
            PaymentAttempt attempt = invocation.getArgument(0);
            ReflectionTestUtils.setField(attempt, "id", 31L);
            return attempt;
        });

        Long retryAttemptId = paymentService.retryPayment(10L, 1L);

        assertThat(retryAttemptId).isEqualTo(31L);
        assertThat(fixture.order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(fixture.payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        ArgumentCaptor<PaymentAttempt> retry = ArgumentCaptor.forClass(PaymentAttempt.class);
        verify(paymentAttemptRepository).save(retry.capture());
        assertThat(retry.getValue().getRequestKey()).isNotEqualTo(fixture.attempt.getRequestKey());

        assertThatThrownBy(() -> paymentService.retryPayment(10L, 2L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void 취소요청중_늦은_승인은_결제를_기록하고_환불후속처리를_남긴다() {
        stubApprovalLocks();
        fixture.order.requestCancellation(null, LocalDateTime.now());
        when(gateway.approve(any())).thenReturn(new PaymentGateway.ApprovalResult(SUCCESS,
                "MOCK-PAY-1", null, null));

        processor.process(30L);

        assertThat(fixture.payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(fixture.order.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(fixture.order.getCancellationReleaseRequired()).isFalse();
    }

    @Test
    void 취소요청중_늦은_거절은_미결제_취소를_완료한다() {
        stubApprovalLocks();
        fixture.order.requestCancellation(null, LocalDateTime.now());
        when(gateway.approve(any())).thenReturn(new PaymentGateway.ApprovalResult(DECLINED,
                null, "DECLINED", "declined"));

        processor.process(30L);

        assertThat(fixture.attempt.getStatus()).isEqualTo(PaymentAttemptStatus.CANCELLED);
        assertThat(fixture.payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(fixture.order.getStatus()).isEqualTo(OrderStatus.CANCEL);
    }

    private Fixture fixture() {
        Product product = new Product();
        product.setId(7L);
        product.setPrice(10_000);
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        ReflectionTestUtils.setField(member, "id", 1L);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery, OrderItem.createOrderItem(product, 10_000, 1));
        ReflectionTestUtils.setField(order, "id", 10L);
        order.markPaymentPending();
        Payment payment = Payment.create(order, 10_000);
        ReflectionTestUtils.setField(payment, "id", 20L);
        PaymentAttempt attempt = PaymentAttempt.create(payment, UUID.randomUUID());
        ReflectionTestUtils.setField(attempt, "id", 30L);
        return new Fixture(order, payment, attempt);
    }

    private record Fixture(Order order, Payment payment, PaymentAttempt attempt) {
    }
}
