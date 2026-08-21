package com.jhg.hgpage.service;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.catalog.ProductRepository;
import com.jhg.hgpage.contract.InventoryPort;
import com.jhg.hgpage.contract.PaymentGateway;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.PaymentAttempt;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.domain.enums.PaymentStatus;
import com.jhg.hgpage.oms.domain.enums.PaymentAttemptStatus;
import com.jhg.hgpage.oms.domain.enums.RefundSourceType;
import com.jhg.hgpage.oms.repository.MemberRepository;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.repository.PaymentAttemptRepository;
import com.jhg.hgpage.oms.repository.PaymentRepository;
import com.jhg.hgpage.oms.repository.RefundRequestRepository;
import com.jhg.hgpage.oms.service.AllocationProcessor;
import com.jhg.hgpage.oms.service.CancellationProcessor;
import com.jhg.hgpage.oms.service.PaymentApprovalProcessor;
import com.jhg.hgpage.oms.service.PaymentFacade;
import com.jhg.hgpage.oms.service.PaymentService;
import com.jhg.hgpage.oms.service.OrderAllocationService;
import com.jhg.hgpage.oms.service.OrderCancellationService.CancellationOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.jhg.hgpage.contract.PaymentGateway.GatewayOutcome.SUCCESS;
import static com.jhg.hgpage.contract.PaymentGateway.GatewayOutcome.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "payments.sweep-delay=1h",
        "allocation.sweep-delay=1h",
        "cancellations.sweep-delay=1h",
        "refunds.sweep-delay=1h",
        "spring.datasource.url=jdbc:h2:mem:payment-cancellation-test"
})
class PaymentCancellationConcurrencyTest {

    @Autowired PaymentApprovalProcessor approvalProcessor;
    @Autowired AllocationProcessor allocationProcessor;
    @Autowired CancellationProcessor cancellationProcessor;
    @Autowired PaymentFacade paymentFacade;
    @Autowired PaymentService paymentService;
    @Autowired OrderAllocationService orderAllocationService;
    @Autowired ProductRepository productRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentAttemptRepository paymentAttemptRepository;
    @Autowired RefundRequestRepository refundRequestRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @MockitoBean PaymentGateway paymentGateway;
    @MockitoBean InventoryPort inventoryPort;

    @Test
    void 승인처리중_취소는_늦은성공후_한번의_전액환불로_수렴한다() throws Exception {
        Fixture fixture = transactionTemplate.execute(status -> pendingPayment());
        CountDownLatch gatewayStarted = new CountDownLatch(1);
        CountDownLatch releaseGateway = new CountDownLatch(1);
        when(paymentGateway.approve(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            gatewayStarted.countDown();
            assertThat(releaseGateway.await(10, TimeUnit.SECONDS)).isTrue();
            return new PaymentGateway.ApprovalResult(SUCCESS, "MOCK-PAY-1", null, null);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> approval = executor.submit(() -> approvalProcessor.process(fixture.attemptId));
            assertThat(gatewayStarted.await(10, TimeUnit.SECONDS)).isTrue();
            paymentFacade.cancelOrder(fixture.orderId, fixture.memberId);
            assertThat(orderRepository.findById(fixture.orderId).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.CANCEL_REQUESTED);
            releaseGateway.countDown();
            approval.get(10, TimeUnit.SECONDS);
        } finally {
            releaseGateway.countDown();
            executor.shutdownNow();
        }

        cancellationProcessor.process(fixture.orderId);

        assertCancelledWithOneRefund(fixture.orderId, 10_000);
    }

    @Test
    void 재시도대기중_취소는_같은결제키의_늦은성공후_환불로_수렴한다() {
        Fixture fixture = transactionTemplate.execute(status -> pendingPayment());
        UUID requestKey = transactionTemplate.execute(status -> {
            PaymentAttempt attempt = paymentAttemptRepository.findByIdForUpdate(fixture.attemptId).orElseThrow();
            attempt.claim(LocalDateTime.now());
            attempt.retryAt(LocalDateTime.now().minusSeconds(1), "UNKNOWN", "unknown");
            return attempt.getRequestKey();
        });

        assertThat(paymentFacade.cancelOrder(fixture.orderId, fixture.memberId))
                .isEqualTo(CancellationOutcome.PENDING);
        when(paymentGateway.approve(any())).thenReturn(
                new PaymentGateway.ApprovalResult(SUCCESS, "MOCK-PAY-RETRY", null, null));

        approvalProcessor.process(fixture.attemptId);
        cancellationProcessor.process(fixture.orderId);

        assertCancelledWithOneRefund(fixture.orderId, 10_000);
        assertThat(paymentAttemptRepository.findById(fixture.attemptId).orElseThrow().getRequestKey())
                .isEqualTo(requestKey);
    }

    @Test
    void 결제검토중_취소는_명시적_재큐후_같은키로_확정해_환불로_수렴한다() {
        Fixture fixture = transactionTemplate.execute(status -> pendingPayment());
        transactionTemplate.executeWithoutResult(status -> {
            PaymentAttempt attempt = paymentAttemptRepository.findByIdForUpdate(fixture.attemptId).orElseThrow();
            for (int count = 1; count < 5; count++) {
                attempt.claim(LocalDateTime.now());
                attempt.retryAt(LocalDateTime.now().minusSeconds(1), "UNKNOWN", "unknown");
            }
        });
        when(paymentGateway.approve(any())).thenReturn(
                new PaymentGateway.ApprovalResult(UNKNOWN, null, "UNKNOWN", "unknown"));
        approvalProcessor.process(fixture.attemptId);

        assertThat(paymentFacade.cancelOrder(fixture.orderId, fixture.memberId))
                .isEqualTo(CancellationOutcome.PENDING);
        assertThat(paymentService.findCancellationReviewAttemptIds()).contains(fixture.attemptId);
        assertThat(paymentService.requeueCancellationReview(fixture.attemptId)).isTrue();
        UUID requestKey = paymentAttemptRepository.findById(fixture.attemptId).orElseThrow().getRequestKey();
        when(paymentGateway.approve(any())).thenReturn(
                new PaymentGateway.ApprovalResult(SUCCESS, "MOCK-PAY-MANUAL", null, null));

        approvalProcessor.process(fixture.attemptId);
        cancellationProcessor.process(fixture.orderId);

        assertCancelledWithOneRefund(fixture.orderId, 10_000);
        assertThat(paymentAttemptRepository.findById(fixture.attemptId).orElseThrow().getRequestKey())
                .isEqualTo(requestKey);
    }

    @Test
    void 할당처리중_취소는_예약성공을_해제한뒤_한번의_전액환불로_수렴한다() throws Exception {
        Fixture fixture = transactionTemplate.execute(status -> paidAllocationPending());
        CountDownLatch wmsStarted = new CountDownLatch(1);
        CountDownLatch releaseWms = new CountDownLatch(1);
        when(inventoryPort.reserveAll(eq(fixture.orderId), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            wmsStarted.countDown();
            assertThat(releaseWms.await(10, TimeUnit.SECONDS)).isTrue();
            return true;
        });
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(inventoryPort).releaseAll(eq(fixture.orderId), any());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> allocation = executor.submit(() -> allocationProcessor.process(fixture.orderId));
            assertThat(wmsStarted.await(10, TimeUnit.SECONDS)).isTrue();
            paymentFacade.cancelOrder(fixture.orderId, fixture.memberId);
            releaseWms.countDown();
            allocation.get(10, TimeUnit.SECONDS);
        } finally {
            releaseWms.countDown();
            executor.shutdownNow();
        }

        cancellationProcessor.process(fixture.orderId);

        assertCancelledWithOneRefund(fixture.orderId, 10_000);
        verify(inventoryPort, times(1)).releaseAll(eq(fixture.orderId), any());
    }

    @Test
    void 할당재시도대기중_취소는_같은주문예약을_확인하고_해제후_환불로_수렴한다() {
        Fixture fixture = transactionTemplate.execute(status -> paidAllocationPending());
        transactionTemplate.executeWithoutResult(status -> {
            Order order = orderRepository.findByIdForUpdate(fixture.orderId).orElseThrow();
            order.claimAllocation(LocalDateTime.now());
            order.retryAllocation(LocalDateTime.now().minusSeconds(1), "WMS_UNAVAILABLE");
        });
        when(inventoryPort.reserveAll(eq(fixture.orderId), any())).thenReturn(true);

        assertThat(paymentFacade.cancelOrder(fixture.orderId, fixture.memberId))
                .isEqualTo(CancellationOutcome.REFUND_PENDING);
        allocationProcessor.process(fixture.orderId);
        cancellationProcessor.process(fixture.orderId);

        assertCancelledWithOneRefund(fixture.orderId, 10_000);
        verify(inventoryPort).releaseAll(eq(fixture.orderId), any());
    }

    @Test
    void 취소중_할당재시도_소진도_명시적_재큐후_예약해제와_환불로_수렴한다() {
        Fixture fixture = transactionTemplate.execute(status -> paidAllocationPending());
        transactionTemplate.executeWithoutResult(status -> {
            Order order = orderRepository.findByIdForUpdate(fixture.orderId).orElseThrow();
            for (int count = 1; count < 5; count++) {
                order.claimAllocation(LocalDateTime.now());
                order.retryAllocation(LocalDateTime.now().minusSeconds(1), "WMS_UNAVAILABLE");
            }
            order.claimAllocation(LocalDateTime.now());
        });
        assertThat(paymentFacade.cancelOrder(fixture.orderId, fixture.memberId))
                .isEqualTo(CancellationOutcome.REFUND_PENDING);
        orderAllocationService.retryOrReview(fixture.orderId, 5, "WMS_UNAVAILABLE");

        assertThat(orderAllocationService.findCancellationAllocationReviewOrderIds())
                .contains(fixture.orderId);
        assertThat(orderAllocationService.requeueCancellationAllocation(fixture.orderId)).isTrue();
        when(inventoryPort.reserveAll(eq(fixture.orderId), any())).thenReturn(true);

        allocationProcessor.process(fixture.orderId);
        cancellationProcessor.process(fixture.orderId);

        assertCancelledWithOneRefund(fixture.orderId, 10_000);
        verify(inventoryPort).releaseAll(eq(fixture.orderId), any());
    }

    @Test
    void 다섯번의_불명확한_할당후_취소도_재큐해_예약해제와_환불로_수렴한다() {
        Fixture fixture = transactionTemplate.execute(status -> paidAllocationPending());
        when(inventoryPort.reserveAll(eq(fixture.orderId), any()))
                .thenThrow(new ResourceAccessException("unknown-1"))
                .thenThrow(new ResourceAccessException("unknown-2"))
                .thenThrow(new ResourceAccessException("unknown-3"))
                .thenThrow(new ResourceAccessException("unknown-4"))
                .thenThrow(new ResourceAccessException("unknown-5"))
                .thenReturn(true);

        for (int attempt = 1; attempt <= 5; attempt++) {
            allocationProcessor.process(fixture.orderId);
            if (attempt < 5) {
                transactionTemplate.executeWithoutResult(status -> ReflectionTestUtils.setField(
                        orderRepository.findByIdForUpdate(fixture.orderId).orElseThrow(),
                        "nextAllocationAttemptAt", LocalDateTime.now().minusSeconds(1)));
            }
        }
        assertThat(orderRepository.findById(fixture.orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.ALLOCATION_REVIEW);
        assertThat(orderRepository.findById(fixture.orderId).orElseThrow().getAllocationFailureCode())
                .isEqualTo("WMS_UNAVAILABLE");

        assertThat(paymentFacade.cancelOrder(fixture.orderId, fixture.memberId))
                .isEqualTo(CancellationOutcome.REFUND_PENDING);
        assertThat(orderRepository.findById(fixture.orderId).orElseThrow().getCancellationReleaseRequired())
                .isNull();
        assertThat(orderAllocationService.findCancellationAllocationReviewOrderIds())
                .contains(fixture.orderId);
        assertThat(orderAllocationService.requeueCancellationAllocation(fixture.orderId)).isTrue();

        allocationProcessor.process(fixture.orderId);
        cancellationProcessor.process(fixture.orderId);

        assertCancelledWithOneRefund(fixture.orderId, 10_000);
        verify(inventoryPort).releaseAll(eq(fixture.orderId), any());
    }

    @Test
    void 재결제와_취소가_경합해도_새시도를_놓치지않고_취소로_수렴한다() throws Exception {
        Fixture fixture = transactionTemplate.execute(status -> failedPayment());
        CountDownLatch gatewayStarted = new CountDownLatch(1);
        CountDownLatch releaseGateway = new CountDownLatch(1);
        when(paymentGateway.approve(any())).thenAnswer(invocation -> {
            gatewayStarted.countDown();
            assertThat(releaseGateway.await(10, TimeUnit.SECONDS)).isTrue();
            return new PaymentGateway.ApprovalResult(SUCCESS, "MOCK-PAY-RETRY-RACE", null, null);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> retry = executor.submit(() -> paymentFacade.retryPayment(fixture.orderId, fixture.memberId));
            assertThat(gatewayStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(paymentFacade.cancelOrder(fixture.orderId, fixture.memberId))
                    .isEqualTo(CancellationOutcome.PENDING);
            releaseGateway.countDown();
            retry.get(10, TimeUnit.SECONDS);
        } finally {
            releaseGateway.countDown();
            executor.shutdownNow();
        }

        cancellationProcessor.process(fixture.orderId);
        assertCancelledWithOneRefund(fixture.orderId, 10_000);
        assertThat(paymentAttemptRepository.findAll().stream()
                .filter(attempt -> attempt.getStatus() == PaymentAttemptStatus.PROCESSING)).isEmpty();
    }

    @Test
    void 동시에_중복취소해도_전액환불은_한번만_예약한다() throws Exception {
        Fixture fixture = transactionTemplate.execute(status -> paidAllocationPending());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<CancellationOutcome> first = executor.submit(() -> cancelAfter(start, fixture));
            Future<CancellationOutcome> second = executor.submit(() -> cancelAfter(start, fixture));
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(CancellationOutcome.REFUND_PENDING);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(CancellationOutcome.REFUND_PENDING);
        } finally {
            executor.shutdownNow();
        }

        assertCancelledWithOneRefund(fixture.orderId, 10_000);
    }

    @Test
    void 두_취소처리기가_경합해도_WMS해제와_전액환불은_한번이다() throws Exception {
        Fixture fixture = transactionTemplate.execute(status -> paidOrder());
        paymentFacade.cancelOrder(fixture.orderId, fixture.memberId);
        CountDownLatch wmsStarted = new CountDownLatch(1);
        CountDownLatch releaseWms = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            wmsStarted.countDown();
            assertThat(releaseWms.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(inventoryPort).releaseAll(eq(fixture.orderId), any());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> cancellationProcessor.process(fixture.orderId));
            assertThat(wmsStarted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> cancellationProcessor.process(fixture.orderId));
            second.get(10, TimeUnit.SECONDS);
            verify(inventoryPort, times(1)).releaseAll(eq(fixture.orderId), any());
            releaseWms.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally {
            releaseWms.countDown();
            executor.shutdownNow();
        }

        assertCancelledWithOneRefund(fixture.orderId, 10_000);
        verify(inventoryPort, times(1)).releaseAll(eq(fixture.orderId), any());
    }

    private void assertCancelledWithOneRefund(Long orderId, int amount) {
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCEL);
            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(payment.getPendingRefundAmount()).isEqualTo(amount);
            assertThat(refundRequestRepository.findBySourceTypeAndSourceId(
                    RefundSourceType.ORDER_CANCEL, orderId)).isPresent();
        });
    }

    private Fixture pendingPayment() {
        Persisted persisted = order();
        persisted.order.markPaymentPending();
        Payment payment = paymentRepository.save(Payment.create(persisted.order, 10_000));
        PaymentAttempt attempt = paymentAttemptRepository.save(PaymentAttempt.create(payment, UUID.randomUUID()));
        return new Fixture(persisted.order.getId(), persisted.member.getId(), attempt.getId());
    }

    private Fixture paidAllocationPending() {
        Persisted persisted = order();
        persisted.order.markPaymentPending();
        Payment payment = Payment.create(persisted.order, 10_000);
        payment.markPaid(LocalDateTime.now());
        paymentRepository.save(payment);
        persisted.order.markAllocationPending();
        return new Fixture(persisted.order.getId(), persisted.member.getId(), null);
    }

    private Fixture failedPayment() {
        Fixture fixture = pendingPayment();
        PaymentAttempt attempt = paymentAttemptRepository.findById(fixture.attemptId).orElseThrow();
        attempt.claim(LocalDateTime.now());
        attempt.fail("DECLINED", "declined", LocalDateTime.now());
        Payment payment = paymentRepository.findByOrderId(fixture.orderId).orElseThrow();
        payment.markPaymentFailed();
        orderRepository.findById(fixture.orderId).orElseThrow().markPaymentFailed();
        return fixture;
    }

    private Fixture paidOrder() {
        Persisted persisted = order();
        persisted.order.markPaymentPending();
        Payment payment = Payment.create(persisted.order, 10_000);
        payment.markPaid(LocalDateTime.now());
        paymentRepository.save(payment);
        persisted.order.markOrdered();
        return new Fixture(persisted.order.getId(), persisted.member.getId(), null);
    }

    private CancellationOutcome cancelAfter(CountDownLatch start, Fixture fixture) throws InterruptedException {
        start.await();
        return paymentFacade.cancelOrder(fixture.orderId, fixture.memberId);
    }

    private Persisted order() {
        Product product = new Product();
        product.setName("경합 상품");
        product.setPrice(10_000);
        productRepository.save(product);
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        memberRepository.save(member);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery,
                OrderItem.createOrderItem(product, product.getPrice(), 1));
        orderRepository.save(order);
        return new Persisted(order, member);
    }

    private record Fixture(Long orderId, Long memberId, Long attemptId) {
    }

    private record Persisted(Order order, Member member) {
    }
}
