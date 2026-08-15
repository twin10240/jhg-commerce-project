package com.jhg.hgpage.repository;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.PaymentAttempt;
import com.jhg.hgpage.oms.domain.RefundRequest;
import com.jhg.hgpage.oms.domain.enums.RefundSourceType;
import com.jhg.hgpage.oms.repository.PaymentAttemptRepository;
import com.jhg.hgpage.oms.repository.PaymentRepository;
import com.jhg.hgpage.oms.repository.RefundRequestRepository;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class PaymentRepositoryTest {

    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentAttemptRepository paymentAttemptRepository;
    @Autowired RefundRequestRepository refundRequestRepository;
    @Autowired TestEntityManager em;

    @Test
    void 주문당_결제는_하나이고_잠금조회가_가능하다() {
        Payment payment = savePayment();
        em.clear();

        assertThat(paymentRepository.findByOrderId(payment.getOrder().getId())).isPresent();
        assertThat(paymentRepository.findByOrderIdForUpdate(payment.getOrder().getId())).isPresent();

        Payment duplicate = Payment.create(payment.getOrder(), payment.getOrderAmount());
        em.persist(duplicate);
        assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
    }

    @Test
    void 결제시도_멱등키는_유일하다() {
        Payment payment = savePayment();
        UUID paymentKey = UUID.randomUUID();
        PaymentAttempt attempt = PaymentAttempt.create(payment, paymentKey);
        em.persistAndFlush(attempt);

        em.persist(PaymentAttempt.create(payment, paymentKey));
        assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
    }

    @Test
    void 환불원천은_유일하다() {
        Payment payment = savePayment();
        RefundRequest refund = RefundRequest.create(payment, UUID.randomUUID(), RefundSourceType.RETURN, 9L, 1_000);
        em.persistAndFlush(refund);
        assertThat(refundRequestRepository.findBySourceTypeAndSourceId(RefundSourceType.RETURN, 9L)).isPresent();

        em.persist(RefundRequest.create(payment, UUID.randomUUID(), RefundSourceType.RETURN, 9L, 1_000));
        assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
    }

    @Test
    void 환불요청_멱등키는_유일하다() {
        Payment payment = savePayment();
        UUID requestKey = UUID.randomUUID();
        em.persistAndFlush(RefundRequest.create(payment, requestKey, RefundSourceType.RETURN, 9L, 1_000));

        em.persist(RefundRequest.create(payment, requestKey, RefundSourceType.ORDER_CANCEL, 10L, 1_000));
        assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
    }

    private Payment savePayment() {
        Product product = new Product();
        product.setName("상품");
        product.setPrice(10_000);
        em.persist(product);
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        em.persist(member);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery, OrderItem.createOrderItem(product, 10_000, 1));
        em.persistAndFlush(order);
        Payment payment = Payment.create(order, 10_000);
        return em.persistAndFlush(payment);
    }
}
