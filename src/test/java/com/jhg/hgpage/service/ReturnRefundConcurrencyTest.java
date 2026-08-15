package com.jhg.hgpage.service;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.contract.ReturnPort.ResultItem;
import com.jhg.hgpage.contract.ReturnPort.ReturnResult;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.domain.enums.RefundSourceType;
import com.jhg.hgpage.oms.repository.CustomerReturnRepository;
import com.jhg.hgpage.oms.repository.PaymentRepository;
import com.jhg.hgpage.oms.repository.RefundRequestRepository;
import com.jhg.hgpage.oms.service.ReturnSyncService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "payments.sweep-delay=1h",
        "allocations.sweep-delay=1h",
        "cancellations.sweep-delay=1h",
        "refunds.sweep-delay=1h",
        "returns.sweep-delay=1h",
        "backorder.sweep-delay=1h",
        "spring.datasource.url=jdbc:h2:mem:return-refund-concurrency-test"
})
class ReturnRefundConcurrencyTest {

    @Autowired ReturnSyncService returnSyncService;
    @Autowired CustomerReturnRepository customerReturnRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired RefundRequestRepository refundRequestRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManager em;

    @Test
    void 동시에_같은_완료_콜백을_받아도_환불요청은_하나만_만든다() throws Exception {
        Fixture fixture = transactionTemplate.execute(status -> pendingReturn());
        CountDownLatch firstApplied = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                returnSyncService.apply(fixture.completed());
                firstApplied.countDown();
                await(releaseFirst);
            }));
            assertThat(firstApplied.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> {
                secondStarted.countDown();
                try {
                    returnSyncService.apply(fixture.completed());
                } finally {
                    secondFinished.countDown();
                }
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(secondFinished.await(200, TimeUnit.MILLISECONDS)).isFalse();

            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }

        assertThat(customerReturnRepository.findDetailedById(fixture.returnId()).orElseThrow().getStatus())
                .isEqualTo(CustomerReturnStatus.COMPLETED);
        assertThat(refundRequestRepository.findAll().stream()
                .filter(refund -> refund.getSourceType() == RefundSourceType.RETURN
                        && refund.getSourceId().equals(fixture.returnId())))
                .singleElement()
                .satisfies(refund -> assertThat(refund.getAmount()).isEqualTo(10_000));
        assertThat(paymentRepository.findByOrderId(fixture.orderId()).orElseThrow().getPendingRefundAmount())
                .isEqualTo(10_000);
    }

    private Fixture pendingReturn() {
        Product product = new Product();
        product.setName("동시성 반품 상품");
        product.setPrice(10_000);
        em.persist(product);
        Member member = Member.createUser("테스터", "010-0000-0000",
                new Address("서울", "관악구", "500"));
        em.persist(member);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        OrderItem item = OrderItem.createOrderItem(product, product.getPrice(), 1);
        Order order = Order.createOrder(member, delivery, item);
        order.ship();
        order.deliver();
        em.persist(order);
        Payment payment = Payment.create(order, order.getTotalPrice());
        payment.markPaid(LocalDateTime.now());
        em.persist(payment);
        CustomerReturn customerReturn = CustomerReturn.create(order, UUID.randomUUID(), "불량",
                List.of(new CustomerReturn.RequestItem(item, 1)));
        em.persist(customerReturn);
        em.flush();
        Long rmaId = 9000L + customerReturn.getId();
        return new Fixture(customerReturn.getId(), order.getId(), new ReturnResult(
                rmaId, customerReturn.getRequestKey(), order.getId(), "COMPLETED", List.of(
                new ResultItem(item.getId(), product.getId(), 1, 1, "RESTOCKED"))));
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("release timeout");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(Long returnId, Long orderId, ReturnResult completed) {}
}
