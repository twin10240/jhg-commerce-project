package com.jhg.hgpage.service;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.contract.PaymentGateway;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.RefundRequest;
import com.jhg.hgpage.oms.domain.enums.RefundSourceType;
import com.jhg.hgpage.oms.domain.enums.RefundStatus;
import com.jhg.hgpage.oms.domain.enums.ReturnDisposition;
import com.jhg.hgpage.oms.repository.PaymentRepository;
import com.jhg.hgpage.oms.repository.RefundRequestRepository;
import com.jhg.hgpage.oms.service.RefundService;
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
import static com.jhg.hgpage.contract.PaymentGateway.GatewayOutcome.PERMANENT_FAILURE;
import static com.jhg.hgpage.contract.PaymentGateway.GatewayOutcome.RETRYABLE_FAILURE;
import static com.jhg.hgpage.contract.PaymentGateway.GatewayOutcome.SUCCESS;
import static com.jhg.hgpage.contract.PaymentGateway.GatewayOutcome.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock RefundRequestRepository refundRequestRepository;

    RefundService service;
    Fixture fixture;

    @BeforeEach
    void setUp() {
        service = new RefundService(paymentRepository, refundRequestRepository, new RetrySchedule());
        fixture = paidOrder(10_000, 2);
    }

    @Test
    void 주문취소_환불은_전액을_pending으로_예약하고_업무원천에_멱등하다() {
        stubPaymentLock();
        when(refundRequestRepository.save(any())).thenAnswer(invocation -> {
            RefundRequest request = invocation.getArgument(0);
            ReflectionTestUtils.setField(request, "id", 30L);
            return request;
        });

        Long refundId = service.requestOrderCancellationRefund(10L).orElseThrow();

        assertThat(refundId).isEqualTo(30L);
        assertThat(fixture.payment.getPendingRefundAmount()).isEqualTo(20_000);
        ArgumentCaptor<RefundRequest> saved = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestRepository).save(saved.capture());
        assertThat(saved.getValue().getSourceType()).isEqualTo(RefundSourceType.ORDER_CANCEL);
        assertThat(saved.getValue().getSourceId()).isEqualTo(10L);
        assertThat(saved.getValue().getAmount()).isEqualTo(20_000);

        when(refundRequestRepository.findBySourceTypeAndSourceId(RefundSourceType.ORDER_CANCEL, 10L))
                .thenReturn(Optional.of(saved.getValue()));
        assertThat(service.requestOrderCancellationRefund(10L)).contains(30L);
        assertThat(fixture.payment.getPendingRefundAmount()).isEqualTo(20_000);
    }

    @Test
    void 결제없는_기존주문은_환불요청을_만들지_않는다() {
        when(paymentRepository.findByOrderIdForUpdate(10L)).thenReturn(Optional.empty());

        assertThat(service.requestOrderCancellationRefund(10L)).isEmpty();

        verify(refundRequestRepository, never()).save(any());
    }

    @Test
    void 결제없는_기존반품은_금액오버플로보다_먼저_noop한다() {
        CustomerReturn customerReturn = completedReturnWithoutPayment(Integer.MAX_VALUE, 2);
        when(paymentRepository.findByOrderIdForUpdate(10L)).thenReturn(Optional.empty());

        assertThat(service.requestReturnRefund(customerReturn)).isEmpty();

        verify(refundRequestRepository, never()).save(any());
    }

    @Test
    void 누적_환불예약이_결제액을_넘으면_거절한다() {
        fixture.payment.reserveRefund(1);
        stubPaymentLock();

        assertThatThrownBy(() -> service.requestOrderCancellationRefund(10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("환불 가능 금액");

        verify(refundRequestRepository, never()).save(any());
    }

    @Test
    void 환불예약합계가_int범위를_넘어도_과환불을_거절한다() {
        fixture = paidOrder(Integer.MAX_VALUE, 1);
        fixture.payment.reserveRefund(Integer.MAX_VALUE);
        stubPaymentLock();

        assertThatThrownBy(() -> service.requestOrderCancellationRefund(10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("환불 가능 금액");

        assertThat(fixture.payment.getPendingRefundAmount()).isEqualTo(Integer.MAX_VALUE);
        verify(refundRequestRepository, never()).save(any());
    }

    @Test
    void 반품환불_API는_승인수량과_주문당시가격으로_금액을_계산한다() {
        fixture.order.ship();
        fixture.order.deliver();
        CustomerReturn customerReturn = CustomerReturn.create(fixture.order, UUID.randomUUID(), "부분 불량",
                List.of(new CustomerReturn.RequestItem(fixture.item, 2)));
        customerReturn.approve("admin@example.com");
        ReflectionTestUtils.setField(customerReturn, "id", 40L);
        customerReturn.complete(List.of(new CustomerReturn.ResultItem(
                fixture.item.getId(), 1, ReturnDisposition.DISPOSED)));
        stubPaymentLock();
        when(refundRequestRepository.save(any())).thenAnswer(invocation -> {
            RefundRequest request = invocation.getArgument(0);
            ReflectionTestUtils.setField(request, "id", 31L);
            return request;
        });

        assertThat(service.requestReturnRefund(customerReturn)).contains(31L);

        ArgumentCaptor<RefundRequest> saved = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestRepository).save(saved.capture());
        assertThat(saved.getValue().getSourceType()).isEqualTo(RefundSourceType.RETURN);
        assertThat(saved.getValue().getSourceId()).isEqualTo(40L);
        assertThat(saved.getValue().getAmount()).isEqualTo(10_000);
    }

    @Test
    void 성공은_pending을_refunded로_이동하고_일시실패는_같은키로_재시도한다() {
        RefundRequest request = processingRequest(1);
        UUID requestKey = request.getRequestKey();
        stubResultLocks(request);
        LocalDateTime before = LocalDateTime.now().plusSeconds(59);

        service.applyResult(30L, 1, new PaymentGateway.RefundResult(
                RETRYABLE_FAILURE, null, "TIMEOUT", "timeout"));

        assertThat(request.getStatus()).isEqualTo(RefundStatus.RETRYING);
        assertThat(request.getNextAttemptAt()).isAfter(before);
        assertThat(request.getRequestKey()).isEqualTo(requestKey);
        assertThat(fixture.payment.getPendingRefundAmount()).isEqualTo(20_000);

        ReflectionTestUtils.setField(request, "nextAttemptAt", LocalDateTime.now().minusSeconds(1));
        RefundService.RefundClaim retried = service.claim(30L).orElseThrow();
        assertThat(retried.attemptNumber()).isEqualTo(2);
        assertThat(retried.command().requestKey()).isEqualTo(requestKey);

        service.applyResult(30L, 2, new PaymentGateway.RefundResult(
                SUCCESS, "MOCK-REFUND-1", null, null));

        assertThat(request.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(request.getGatewayTransactionId()).isEqualTo("MOCK-REFUND-1");
        assertThat(fixture.payment.getPendingRefundAmount()).isZero();
        assertThat(fixture.payment.getRefundedAmount()).isEqualTo(20_000);
    }

    @Test
    void 결과불명도_같은키로_재시도한다() {
        RefundRequest request = processingRequest(1);
        UUID requestKey = request.getRequestKey();
        stubResultLocks(request);

        service.applyResult(30L, 1, new PaymentGateway.RefundResult(
                UNKNOWN, null, "UNKNOWN", "unknown"));

        assertThat(request.getStatus()).isEqualTo(RefundStatus.RETRYING);
        assertThat(request.getRequestKey()).isEqualTo(requestKey);
        assertThat(fixture.payment.getPendingRefundAmount()).isEqualTo(20_000);
    }

    @Test
    void 거절과_영구실패_다섯번째_일시실패는_manual_review에_남긴다() {
        for (PaymentGateway.GatewayOutcome outcome : List.of(DECLINED, PERMANENT_FAILURE)) {
            fixture = paidOrder(10_000, 2);
            RefundRequest permanent = processingRequest(1);
            stubResultLocks(permanent);

            service.applyResult(30L, 1, new PaymentGateway.RefundResult(
                    outcome, null, "INVALID_AMOUNT", "invalid"));

            assertThat(permanent.getStatus()).isEqualTo(RefundStatus.MANUAL_REVIEW);
            assertThat(fixture.payment.getPendingRefundAmount()).isEqualTo(20_000);
        }

        fixture = paidOrder(10_000, 2);
        RefundRequest exhausted = processingRequest(5);
        stubResultLocks(exhausted);
        service.applyResult(30L, 5, new PaymentGateway.RefundResult(
                RETRYABLE_FAILURE, null, "TIMEOUT", "timeout"));

        assertThat(exhausted.getStatus()).isEqualTo(RefundStatus.MANUAL_REVIEW);
        assertThat(fixture.payment.getPendingRefundAmount()).isEqualTo(20_000);
    }

    @Test
    void stale_작업A의_결과는_재선점한_작업B를_변경하지_못한다() {
        RefundRequest request = processingRequest(1);
        LocalDateTime now = LocalDateTime.now();
        ReflectionTestUtils.setField(request, "updatedAt", now.minusMinutes(10));
        when(refundRequestRepository.findTop50ByStatusAndUpdatedAtLessThanEqualOrderById(
                RefundStatus.PROCESSING, now.minusMinutes(5))).thenReturn(List.of(request));

        service.recoverStaleRefunds(now.minusMinutes(5), now);
        RefundService.RefundClaim attemptB = service.claim(30L).orElseThrow();
        stubResultLocks(request);

        service.applyResult(30L, 1, new PaymentGateway.RefundResult(
                SUCCESS, "STALE", null, null));

        assertThat(request.getStatus()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(fixture.payment.getPendingRefundAmount()).isEqualTo(20_000);

        service.applyResult(30L, attemptB.attemptNumber(), new PaymentGateway.RefundResult(
                SUCCESS, "CURRENT", null, null));
        assertThat(request.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
    }

    @Test
    void 수동검토_환불만_같은키와_시도횟수로_재큐한다() {
        RefundRequest request = processingRequest(1);
        request.manualReview("INVALID_AMOUNT", "invalid", LocalDateTime.now());
        UUID requestKey = request.getRequestKey();
        int attemptCount = request.getAttemptCount();

        assertThat(service.requeueReview(30L)).isTrue();
        assertThat(request.getStatus()).isEqualTo(RefundStatus.RETRYING);
        assertThat(request.getRequestKey()).isEqualTo(requestKey);
        assertThat(request.getAttemptCount()).isEqualTo(attemptCount);
        assertThat(service.requeueReview(30L)).isFalse();
    }

    private void stubPaymentLock() {
        when(paymentRepository.findByOrderIdForUpdate(10L)).thenReturn(Optional.of(fixture.payment));
    }

    private void stubResultLocks(RefundRequest request) {
        when(paymentRepository.findByOrderIdForUpdate(10L)).thenReturn(Optional.of(fixture.payment));
        when(refundRequestRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(request));
    }

    private RefundRequest processingRequest(int attempts) {
        fixture.payment.reserveRefund(20_000);
        RefundRequest request = RefundRequest.create(fixture.payment, UUID.randomUUID(),
                RefundSourceType.ORDER_CANCEL, 10L, 20_000);
        ReflectionTestUtils.setField(request, "id", 30L);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            request.claim(LocalDateTime.now());
            if (attempt < attempts) {
                request.retryAt(LocalDateTime.now(), "TIMEOUT", "timeout", LocalDateTime.now());
            }
        }
        when(refundRequestRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(request));
        return request;
    }

    private Fixture paidOrder(int price, int quantity) {
        Product product = new Product();
        product.setId(7L);
        product.setName("상품");
        product.setPrice(price);
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        ReflectionTestUtils.setField(member, "id", 1L);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        OrderItem item = OrderItem.createOrderItem(product, price, quantity);
        ReflectionTestUtils.setField(item, "id", 11L);
        Order order = Order.createOrder(member, delivery, item);
        ReflectionTestUtils.setField(order, "id", 10L);
        Payment payment = Payment.create(order, order.getTotalPrice());
        ReflectionTestUtils.setField(payment, "id", 20L);
        payment.markPaid(LocalDateTime.now());
        return new Fixture(order, item, payment);
    }

    private CustomerReturn completedReturnWithoutPayment(int price, int quantity) {
        Product product = new Product();
        product.setId(7L);
        product.setPrice(price);
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        OrderItem item = OrderItem.createOrderItem(product, price, quantity);
        ReflectionTestUtils.setField(item, "id", 11L);
        Order order = Order.createOrder(member, delivery, item);
        ReflectionTestUtils.setField(order, "id", 10L);
        order.markOrdered();
        order.ship();
        order.deliver();
        CustomerReturn customerReturn = CustomerReturn.create(order, UUID.randomUUID(), "기존 반품",
                List.of(new CustomerReturn.RequestItem(item, quantity)));
        customerReturn.approve("admin@example.com");
        ReflectionTestUtils.setField(customerReturn, "id", 40L);
        customerReturn.complete(List.of(new CustomerReturn.ResultItem(
                item.getId(), quantity, ReturnDisposition.DISPOSED)));
        return customerReturn;
    }

    private record Fixture(Order order, OrderItem item, Payment payment) {
    }
}
