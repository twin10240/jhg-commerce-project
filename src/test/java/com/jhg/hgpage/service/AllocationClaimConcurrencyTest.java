package com.jhg.hgpage.service;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.catalog.ProductRepository;
import com.jhg.hgpage.contract.InventoryPort;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.repository.MemberRepository;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.repository.PaymentRepository;
import com.jhg.hgpage.oms.service.AllocationProcessor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "allocation.sweep-delay=1h",
        "spring.datasource.url=jdbc:h2:mem:allocation-claim-test"
})
class AllocationClaimConcurrencyTest {

    @Autowired AllocationProcessor processor;
    @Autowired ProductRepository productRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @MockitoBean InventoryPort inventoryPort;

    @Test
    void 두_처리기가_같은주문을_경합해도_WMS는_한번만_호출한다() throws Exception {
        Long orderId = transactionTemplate.execute(status -> paidPendingOrder());
        UUID requestKey = orderRepository.findById(orderId).orElseThrow().getRequestKey();
        CountDownLatch wmsStarted = new CountDownLatch(1);
        CountDownLatch releaseWms = new CountDownLatch(1);
        when(inventoryPort.reserveAll(eq(requestKey), eq(orderId), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            wmsStarted.countDown();
            assertThat(releaseWms.await(10, TimeUnit.SECONDS)).isTrue();
            return true;
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> processor.process(orderId));
            assertThat(wmsStarted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> processor.process(orderId));
            second.get(10, TimeUnit.SECONDS);

            Order active = orderRepository.findById(orderId).orElseThrow();
            assertThat(active.getStatus()).isEqualTo(OrderStatus.ALLOCATION_PROCESSING);
            assertThat(active.getAllocationAttemptCount()).isEqualTo(1);
            verify(inventoryPort, times(1)).reserveAll(eq(requestKey), eq(orderId), any());

            releaseWms.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally {
            releaseWms.countDown();
            executor.shutdownNow();
        }

        verify(inventoryPort, times(1)).reserveAll(eq(requestKey), eq(orderId), any());
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.ORDER);
    }

    private Long paidPendingOrder() {
        Product product = new Product();
        product.setName("동시성 상품");
        product.setPrice(10_000);
        productRepository.save(product);
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        memberRepository.save(member);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery,
                OrderItem.createOrderItem(product, product.getPrice(), 1));
        order.markPaymentPending();
        orderRepository.save(order);
        Payment payment = Payment.create(order, order.getTotalPrice());
        payment.markPaid(LocalDateTime.now());
        paymentRepository.save(payment);
        order.markAllocationPending();
        return order.getId();
    }
}
