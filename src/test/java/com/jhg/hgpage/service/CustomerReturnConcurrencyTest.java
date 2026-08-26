package com.jhg.hgpage.service;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.catalog.ProductRepository;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.CustomerReturnItem;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.repository.MemberRepository;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.service.CustomerReturnService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CustomerReturnConcurrencyTest {

    @Autowired CustomerReturnService customerReturnService;
    @Autowired ProductRepository productRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void 동시에_한개씩_요청해도_주문수량_한개를_초과하지_않는다() throws Exception {
        Fixture fixture = transactionTemplate.execute(status -> deliveredOneUnitOrder());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Throwable> first = executor.submit(() -> requestAfter(start, fixture));
            Future<Throwable> second = executor.submit(() -> requestAfter(start, fixture));
            start.countDown();

            List<Throwable> results = Arrays.asList(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results).filteredOn(result -> result == null).hasSize(1);
            assertThat(results).filteredOn(result -> result != null).singleElement()
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            executor.shutdownNow();
        }

        int activeQuantity = customerReturnService.findForOwnedOrder(fixture.orderId(), fixture.memberId()).stream()
                .filter(customerReturn -> customerReturn.getStatus() == CustomerReturnStatus.PENDING_APPROVAL
                        || customerReturn.getStatus() == CustomerReturnStatus.PENDING_SUBMISSION
                        || customerReturn.getStatus() == CustomerReturnStatus.REQUESTED
                        || customerReturn.getStatus() == CustomerReturnStatus.RECEIVED)
                .flatMap(customerReturn -> customerReturn.getItems().stream())
                .mapToInt(CustomerReturnItem::getRequestedQuantity)
                .sum();
        assertThat(activeQuantity).isEqualTo(1);
    }

    private Throwable requestAfter(CountDownLatch start, Fixture fixture) {
        try {
            start.await();
            customerReturnService.request(fixture.orderId(), fixture.memberId(), "불량",
                    List.of(new CustomerReturnService.ReturnLine(fixture.orderItemId(), 1)));
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private Fixture deliveredOneUnitOrder() {
        Product product = new Product();
        product.setName("동시성 상품");
        product.setPrice(10000);
        productRepository.save(product);
        Member member = Member.createUser("동시성 테스터", "010-0000-0000",
                new Address("서울", "관악구", "500"));
        memberRepository.save(member);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        OrderItem item = OrderItem.createOrderItem(product, product.getPrice(), 1);
        Order order = Order.createOrder(member, delivery, item);
        order.ship();
        order.deliver();
        orderRepository.save(order);
        return new Fixture(order.getId(), member.getId(), item.getId());
    }

    private record Fixture(Long orderId, Long memberId, Long orderItemId) {}
}
