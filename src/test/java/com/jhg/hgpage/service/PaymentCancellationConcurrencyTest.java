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
        "allocations.sweep-delay=1h",
        "cancellations.sweep-delay=1h",
        "refunds.sweep-delay=1h",
        "spring.datasource.url=jdbc:h2:mem:payment-cancellation-test"
})
class PaymentCancellationConcurrencyTest {

    @Autowired PaymentApprovalProcessor approvalProcessor;
    @Autowired AllocationProcessor allocationProcessor;
    @Autowired CancellationProcessor cancellationProcessor;
    @Autowired PaymentFacade paymentFacade;
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
    void 동시에_중복취소해도_전액환불은_한번만_예약한다() throws Exception {
        Fixture fixture = transactionTemplate.execute(status -> paidAllocationPending());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(() -> cancelAfter(start, fixture));
            Future<Boolean> second = executor.submit(() -> cancelAfter(start, fixture));
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
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

    private Fixture paidOrder() {
        Persisted persisted = order();
        persisted.order.markPaymentPending();
        Payment payment = Payment.create(persisted.order, 10_000);
        payment.markPaid(LocalDateTime.now());
        paymentRepository.save(payment);
        persisted.order.markOrdered();
        return new Fixture(persisted.order.getId(), persisted.member.getId(), null);
    }

    private boolean cancelAfter(CountDownLatch start, Fixture fixture) throws InterruptedException {
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
