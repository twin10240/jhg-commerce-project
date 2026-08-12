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
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.repository.CustomerReturnRepository;
import com.jhg.hgpage.oms.service.CustomerReturnService;
import com.jhg.hgpage.oms.service.ReturnSyncService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ReturnMutationConcurrencyTest.RaceWriter.class)
class ReturnMutationConcurrencyTest {

    @Autowired RaceWriter raceWriter;
    @Autowired ReturnSyncService returnSyncService;
    @Autowired CustomerReturnRepository repository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManager em;

    @Test
    void 수령_스윕과_완료_콜백이_겹쳐도_완료가_회귀하지_않는다() throws Exception {
        Fixture fixture = pendingReturn();
        CountDownLatch receivedApplied = new CountDownLatch(1);
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch callbackFinished = new CountDownLatch(1);
        CountDownLatch releaseReceived = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> received = executor.submit(() -> raceWriter.applyAndHold(
                    fixture.received(), receivedApplied, releaseReceived));
            assertThat(receivedApplied.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> callback = executor.submit(() -> {
                callbackStarted.countDown();
                try {
                    returnSyncService.apply(fixture.completed());
                } finally {
                    callbackFinished.countDown();
                }
            });
            assertThat(callbackStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(callbackFinished.await(200, TimeUnit.MILLISECONDS)).isFalse();
            releaseReceived.countDown();
            received.get(5, TimeUnit.SECONDS);
            callback.get(5, TimeUnit.SECONDS);
        } finally {
            releaseReceived.countDown();
            executor.shutdownNow();
        }

        assertThat(repository.findDetailedById(fixture.returnId()).orElseThrow().getStatus())
                .isEqualTo(CustomerReturnStatus.COMPLETED);
    }

    private Fixture pendingReturn() {
        return transactionTemplate.execute(status -> {
            Product product = new Product();
            product.setName("동시성 상품");
            product.setPrice(10000);
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
            em.flush();
            UUID requestKey = UUID.randomUUID();
            CustomerReturn customerReturn = CustomerReturn.create(order, requestKey, "불량",
                    List.of(new CustomerReturn.RequestItem(item, 1)));
            em.persist(customerReturn);
            em.flush();
            Long rmaId = 9000L + customerReturn.getId();
            customerReturn.markRequested(rmaId);
            em.flush();
            List<ResultItem> items = List.of(new ResultItem(item.getId(), product.getId(), 1, 0, null));
            return new Fixture(customerReturn.getId(), rmaId, new ReturnResult(rmaId, requestKey,
                    order.getId(), "RECEIVED", items), new ReturnResult(rmaId, requestKey,
                    order.getId(), "COMPLETED", List.of(new ResultItem(
                    item.getId(), product.getId(), 1, 1, "RESTOCKED"))));
        });
    }

    record Fixture(Long returnId, Long rmaId, ReturnResult received, ReturnResult completed) {}

    @Component
    static class RaceWriter {
        private final ReturnSyncService returnSyncService;

        RaceWriter(ReturnSyncService returnSyncService) {
            this.returnSyncService = returnSyncService;
        }

        @Transactional
        public void applyAndHold(ReturnResult result, CountDownLatch mutated, CountDownLatch release) {
            returnSyncService.apply(result);
            mutated.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
    }
}
