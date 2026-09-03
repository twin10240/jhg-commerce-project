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
import com.jhg.hgpage.oms.domain.enums.PaymentAttemptStatus;
import com.jhg.hgpage.oms.repository.MemberRepository;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.repository.PaymentAttemptRepository;
import com.jhg.hgpage.oms.repository.PaymentRepository;
import com.jhg.hgpage.oms.service.AllocationProcessor;
import com.jhg.hgpage.oms.service.CancellationProcessor;
import com.jhg.hgpage.oms.service.OrderAllocationService;
import com.jhg.hgpage.oms.service.PaymentAdminService;
import com.jhg.hgpage.oms.service.PaymentApprovalProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.jhg.hgpage.contract.PaymentGateway.GatewayOutcome.SUCCESS;
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
        "spring.datasource.url=jdbc:h2:mem:payment-admin-concurrency-test"
})
class PaymentAdminConcurrencyTest {

    @Autowired PaymentAdminService paymentAdminService;
    @Autowired PaymentApprovalProcessor paymentApprovalProcessor;
    @Autowired AllocationProcessor allocationProcessor;
    @Autowired CancellationProcessor cancellationProcessor;
    @Autowired OrderAllocationService orderAllocationService;
    @Autowired ProductRepository productRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentAttemptRepository paymentAttemptRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @MockitoBean PaymentGateway paymentGateway;
    @MockitoBean InventoryPort inventoryPort;

    @Test
    void 취소결제검토_관리자와_처리기가_경합해도_같은키로_한번만_승인한다() throws Exception {
        PaymentFixture fixture = transactionTemplate.execute(status -> cancellationPaymentReview());
        long workCount = paymentAttemptRepository.count();
        CountDownLatch gatewayStarted = new CountDownLatch(1);
        CountDownLatch releaseGateway = new CountDownLatch(1);
        when(paymentGateway.approve(any())).thenAnswer(invocation -> {
            PaymentGateway.ApprovalCommand command = invocation.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(command.orderId()).isEqualTo(fixture.orderId());
            assertThat(command.requestKey()).isEqualTo(fixture.requestKey());
            gatewayStarted.countDown();
            assertThat(releaseGateway.await(10, TimeUnit.SECONDS)).isTrue();
            return new PaymentGateway.ApprovalResult(SUCCESS, "MOCK-PAY-ADMIN", null, null);
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> admin = executor.submit(
                    () -> paymentAdminService.retryCancellationPayment(fixture.attemptId()));
            assertThat(gatewayStarted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> scheduled = executor.submit(
                    () -> paymentApprovalProcessor.process(fixture.attemptId()));
            scheduled.get(10, TimeUnit.SECONDS);

            PaymentAttempt processing = paymentAttemptRepository.findById(fixture.attemptId()).orElseThrow();
            assertThat(processing.getAttemptCount()).isEqualTo(6);
            assertThat(processing.getRequestKey()).isEqualTo(fixture.requestKey());
            assertThat(paymentAttemptRepository.count()).isEqualTo(workCount);
            verify(paymentGateway, times(1)).approve(any());

            releaseGateway.countDown();
            admin.get(10, TimeUnit.SECONDS);
        } finally {
            releaseGateway.countDown();
            executor.shutdownNow();
        }

        PaymentAttempt completed = paymentAttemptRepository.findById(fixture.attemptId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(completed.getAttemptCount()).isEqualTo(6);
        assertThat(completed.getRequestKey()).isEqualTo(fixture.requestKey());
        assertThat(paymentAttemptRepository.count()).isEqualTo(workCount);
        assertThat(orderRepository.findById(fixture.orderId()).orElseThrow().getCancellationReleaseRequired())
                .isFalse();
        verify(paymentGateway, times(1)).approve(any());
    }

    @Test
    void 미확정_취소할당_관리자와_처리기가_경합해도_같은주문을_한번만_예약한다() throws Exception {
        Long orderId = transactionTemplate.execute(status -> unresolvedAllocationReview());
        UUID orderRequestKey = orderRepository.findById(orderId).orElseThrow().getRequestKey();
        orderAllocationService.retryOrReview(orderId, 5, "WMS_UNAVAILABLE");
        long workCount = orderRepository.count();
        CountDownLatch wmsStarted = new CountDownLatch(1);
        CountDownLatch releaseWms = new CountDownLatch(1);
        when(inventoryPort.reserveAll(eq(orderRequestKey), eq(orderId), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(invocation.getArgument(0, UUID.class)).isEqualTo(orderRequestKey);
            wmsStarted.countDown();
            assertThat(releaseWms.await(10, TimeUnit.SECONDS)).isTrue();
            return true;
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> admin = executor.submit(() -> paymentAdminService.retryAllocation(orderId));
            assertThat(wmsStarted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> scheduled = executor.submit(() -> allocationProcessor.process(orderId));
            scheduled.get(10, TimeUnit.SECONDS);

            Order processing = orderRepository.findById(orderId).orElseThrow();
            assertThat(processing.getId()).isEqualTo(orderId);
            assertThat(processing.getAllocationAttemptCount()).isEqualTo(6);
            assertThat(orderRepository.count()).isEqualTo(workCount);
            verify(inventoryPort, times(1)).reserveAll(eq(orderRequestKey), eq(orderId), any());

            releaseWms.countDown();
            admin.get(10, TimeUnit.SECONDS);
        } finally {
            releaseWms.countDown();
            executor.shutdownNow();
        }

        Order completed = orderRepository.findById(orderId).orElseThrow();
        assertThat(completed.getId()).isEqualTo(orderId);
        assertThat(completed.getAllocationAttemptCount()).isEqualTo(6);
        assertThat(completed.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        assertThat(completed.getCancellationReleaseRequired()).isTrue();
        assertThat(orderRepository.count()).isEqualTo(workCount);
        verify(inventoryPort, times(1)).reserveAll(eq(orderRequestKey), eq(orderId), any());
    }

    @Test
    void 해제취소검토_관리자와_처리기가_경합해도_같은주문을_한번만_해제한다() throws Exception {
        Long orderId = transactionTemplate.execute(status -> exhaustedReleaseCancellationReview());
        UUID orderRequestKey = orderRepository.findById(orderId).orElseThrow().getRequestKey();
        long workCount = orderRepository.count();
        CountDownLatch wmsStarted = new CountDownLatch(1);
        CountDownLatch releaseWms = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(invocation.getArgument(0, UUID.class)).isEqualTo(orderRequestKey);
            wmsStarted.countDown();
            assertThat(releaseWms.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(inventoryPort).releaseAll(eq(orderRequestKey), any());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> admin = executor.submit(() -> paymentAdminService.retryAllocation(orderId));
            assertThat(wmsStarted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> scheduled = executor.submit(() -> cancellationProcessor.process(orderId));
            scheduled.get(10, TimeUnit.SECONDS);

            Order processing = orderRepository.findById(orderId).orElseThrow();
            assertThat(processing.getId()).isEqualTo(orderId);
            assertThat(processing.getCancellationAttemptCount()).isEqualTo(6);
            assertThat(orderRepository.count()).isEqualTo(workCount);
            verify(inventoryPort, times(1)).releaseAll(eq(orderRequestKey), any());

            releaseWms.countDown();
            admin.get(10, TimeUnit.SECONDS);
        } finally {
            releaseWms.countDown();
            executor.shutdownNow();
        }

        Order completed = orderRepository.findById(orderId).orElseThrow();
        assertThat(completed.getId()).isEqualTo(orderId);
        assertThat(completed.getCancellationAttemptCount()).isEqualTo(6);
        assertThat(completed.getStatus()).isEqualTo(OrderStatus.CANCEL);
        assertThat(orderRepository.count()).isEqualTo(workCount);
        verify(inventoryPort, times(1)).releaseAll(eq(orderRequestKey), any());
    }

    private PaymentFixture cancellationPaymentReview() {
        Persisted persisted = order();
        persisted.order().markPaymentPending();
        Payment payment = paymentRepository.save(Payment.create(persisted.order(), 10_000));
        PaymentAttempt attempt = paymentAttemptRepository.save(
                PaymentAttempt.create(payment, UUID.randomUUID()));
        for (int count = 1; count < 5; count++) {
            attempt.claim(LocalDateTime.now());
            attempt.retryAt(LocalDateTime.now().minusSeconds(1), "UNKNOWN", "unknown");
        }
        attempt.claim(LocalDateTime.now());
        attempt.manualReview("UNKNOWN", "unknown", LocalDateTime.now());
        payment.markPaymentReview();
        persisted.order().markPaymentReview();
        persisted.order().requestCancellation(null, LocalDateTime.now());
        return new PaymentFixture(persisted.order().getId(), attempt.getId(), attempt.getRequestKey());
    }

    private Long unresolvedAllocationReview() {
        Persisted persisted = paidPaymentPending();
        persisted.order().markAllocationPending();
        for (int count = 1; count < 5; count++) {
            persisted.order().claimAllocation(LocalDateTime.now());
            persisted.order().retryAllocation(
                    LocalDateTime.now().minusSeconds(1), "WMS_UNAVAILABLE");
        }
        persisted.order().claimAllocation(LocalDateTime.now());
        persisted.order().requestCancellation(null, LocalDateTime.now());
        return persisted.order().getId();
    }

    private Long exhaustedReleaseCancellationReview() {
        Persisted persisted = paidOrder();
        persisted.order().requestCancellation(true, LocalDateTime.now());
        for (int count = 1; count <= 5; count++) {
            persisted.order().claimCancellation(LocalDateTime.now());
            if (count < 5) {
                persisted.order().retryCancellation(
                        LocalDateTime.now().minusSeconds(1), "WMS_UNAVAILABLE");
            } else {
                persisted.order().reviewCancellation("WMS_UNAVAILABLE");
            }
        }
        return persisted.order().getId();
    }

    private Persisted paidOrder() {
        Persisted persisted = paidPaymentPending();
        persisted.order().markOrdered();
        return persisted;
    }

    private Persisted paidPaymentPending() {
        Persisted persisted = order();
        persisted.order().markPaymentPending();
        Payment payment = Payment.create(persisted.order(), 10_000);
        payment.markPaid(LocalDateTime.now());
        paymentRepository.save(payment);
        return persisted;
    }

    private Persisted order() {
        Product product = new Product();
        product.setName("관리자 경합 상품");
        product.setPrice(10_000);
        productRepository.save(product);
        Member member = Member.createUser(
                "테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        memberRepository.save(member);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery,
                OrderItem.createOrderItem(product, product.getPrice(), 1));
        orderRepository.save(order);
        return new Persisted(order);
    }

    private record PaymentFixture(Long orderId, Long attemptId, UUID requestKey) {
    }

    private record Persisted(Order order) {
    }
}
