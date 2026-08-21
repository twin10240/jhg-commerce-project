package com.jhg.hgpage.repository;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.config.QueryDslConfig;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({QueryDslConfig.class, OrderRepositoryQuery.class})
class OrderCancellationReviewRepositoryTest {

    @Autowired OrderRepositoryQuery query;
    @Autowired OrderRepository repository;
    @Autowired TestEntityManager em;

    @Test
    void 재고취소검토는_미확정할당과_소진된해제를_한목록으로_반환한다() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 15, 12, 0);
        Order exhaustedRelease = order();
        exhaustedRelease.requestCancellation(true, now.minusMinutes(2));
        ReflectionTestUtils.setField(exhaustedRelease, "cancellationAttemptCount", 5);
        ReflectionTestUtils.setField(exhaustedRelease, "cancellationNextAttemptAt", null);
        em.persist(exhaustedRelease);

        Order unresolvedAllocation = order();
        unresolvedAllocation.markPaymentPending();
        unresolvedAllocation.markAllocationPending();
        unresolvedAllocation.claimAllocation(now.minusMinutes(3));
        unresolvedAllocation.requestCancellation(null, now.minusMinutes(3));
        ReflectionTestUtils.setField(unresolvedAllocation, "allocationProcessingAt", null);
        em.persist(unresolvedAllocation);

        Order scheduledRelease = order();
        scheduledRelease.requestCancellation(true, now.minusMinutes(4));
        ReflectionTestUtils.setField(scheduledRelease, "cancellationAttemptCount", 1);
        ReflectionTestUtils.setField(scheduledRelease, "cancellationNextAttemptAt", now.plusMinutes(1));
        em.persist(scheduledRelease);
        em.flush();
        em.clear();

        assertThat(query.findCancellationAllocationReviewOrderIds())
                .containsExactly(unresolvedAllocation.getId(), exhaustedRelease.getId())
                .doesNotContain(scheduledRelease.getId());
    }

    @Test
    void 재기동후_해제처리는_기한이지난_작업만_다시조회한다() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 15, 12, 0);
        Order due = order();
        due.requestCancellation(true, now.minusMinutes(1));
        em.persist(due);

        Order future = order();
        future.requestCancellation(true, now.plusMinutes(1));
        em.persist(future);

        Order manualReview = order();
        manualReview.requestCancellation(true, now.minusMinutes(2));
        ReflectionTestUtils.setField(manualReview, "cancellationAttemptCount", 5);
        ReflectionTestUtils.setField(manualReview, "cancellationNextAttemptAt", null);
        em.persist(manualReview);
        em.flush();
        em.clear();

        assertThat(repository.findDueCancellationOrderIds(now))
                .containsExactly(due.getId())
                .doesNotContain(future.getId(), manualReview.getId());
    }

    private Order order() {
        Product product = new Product();
        product.setName("상품");
        product.setPrice(10_000);
        em.persist(product);
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        em.persist(member);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        return Order.createOrder(member, delivery,
                OrderItem.createOrderItem(product, product.getPrice(), 1));
    }
}
