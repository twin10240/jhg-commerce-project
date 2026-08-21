package com.jhg.hgpage;

import com.jhg.hgpage.oms.domain.Account;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.PaymentAttempt;
import com.jhg.hgpage.oms.domain.RefundRequest;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.domain.enums.PaymentAttemptStatus;
import com.jhg.hgpage.oms.domain.enums.PaymentStatus;
import com.jhg.hgpage.oms.domain.enums.RefundSourceType;
import com.jhg.hgpage.oms.domain.enums.RefundStatus;
import com.jhg.hgpage.oms.domain.enums.ReturnDisposition;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ddl-auto: update 환경에서는 앱 재시작마다 init()이 다시 불리므로,
 * 이미 시드된 DB에 중복 시드(이메일 unique 충돌로 기동 실패)가 없어야 한다.
 * 또한 관리자 비밀번호는 코드에 박지 않고 주입받은 값(운영은 ADMIN_PASSWORD env)으로 시드한다.
 */
@DataJpaTest
class InitDbTest {

    @Autowired EntityManager em;
    @Autowired OrderRepository orderRepository;

    @Test
    void 빈_DB에는_정확한_일곱_결제상태를_완전한_이력으로_한번만_시드한다() {
        initDb.initService service = new initDb.initService(em, "1111");
        initDb db = new initDb(service);

        db.init();
        db.init();

        Long accounts = em.createQuery("select count(a) from Account a", Long.class).getSingleResult();
        Long products = em.createQuery("select count(p) from Product p", Long.class).getSingleResult();
        assertThat(accounts).isEqualTo(2L);
        assertThat(products).isEqualTo(20L);
        assertThat(orderRepository.findAll()).extracting(Order::getStatus)
                .containsExactlyInAnyOrder(
                        OrderStatus.ORDER, OrderStatus.BACKORDERED, OrderStatus.PAYMENT_FAILED,
                        OrderStatus.ORDER, OrderStatus.CANCEL, OrderStatus.CANCEL,
                        OrderStatus.ALLOCATION_REVIEW);

        var payments = em.createQuery("select p from Payment p", Payment.class).getResultList();
        var attempts = em.createQuery("select a from PaymentAttempt a", PaymentAttempt.class).getResultList();
        var refunds = em.createQuery("select r from RefundRequest r", RefundRequest.class).getResultList();
        assertThat(payments).hasSize(7);
        assertThat(attempts).hasSize(7);
        assertThat(attempts).extracting(PaymentAttempt::getStatus)
                .containsExactlyInAnyOrder(
                        PaymentAttemptStatus.SUCCEEDED, PaymentAttemptStatus.SUCCEEDED,
                        PaymentAttemptStatus.FAILED, PaymentAttemptStatus.SUCCEEDED,
                        PaymentAttemptStatus.SUCCEEDED, PaymentAttemptStatus.SUCCEEDED,
                        PaymentAttemptStatus.SUCCEEDED);
        assertThat(attempts).extracting(attempt -> attempt.getPayment().getId()).doesNotHaveDuplicates();
        assertThat(attempts).allSatisfy(attempt -> assertThat(attempt.getAttemptCount()).isEqualTo(1));
        assertThat(attempts).filteredOn(attempt -> attempt.getStatus() == PaymentAttemptStatus.SUCCEEDED)
                .allSatisfy(attempt -> assertThat(attempt.getGatewayTransactionId()).isNotBlank());
        assertThat(attempts).filteredOn(attempt -> attempt.getStatus() == PaymentAttemptStatus.FAILED)
                .singleElement().satisfies(attempt -> assertThat(attempt.getFailureCode()).isEqualTo("SEED_DECLINED"));

        assertThat(payment(OrderStatus.ORDER, PaymentStatus.PAID))
                .extracting(Payment::getOrderAmount, Payment::getPaidAmount,
                        Payment::getPendingRefundAmount, Payment::getRefundedAmount)
                .containsExactly(10_000, 10_000, 0, 0);
        assertThat(payment(OrderStatus.BACKORDERED, PaymentStatus.PAID).getPaidAmount()).isEqualTo(10_000);
        assertThat(payment(OrderStatus.PAYMENT_FAILED, PaymentStatus.PAYMENT_FAILED))
                .extracting(Payment::getOrderAmount, Payment::getPaidAmount)
                .containsExactly(10_000, 0);
        assertThat(payment(OrderStatus.ALLOCATION_REVIEW, PaymentStatus.PAID).getPaidAmount()).isEqualTo(10_000);

        assertThat(refunds).hasSize(3);
        RefundRequest partial = refund(RefundSourceType.RETURN, RefundStatus.SUCCEEDED);
        RefundRequest full = refund(RefundSourceType.ORDER_CANCEL, RefundStatus.SUCCEEDED);
        RefundRequest review = refund(RefundSourceType.ORDER_CANCEL, RefundStatus.MANUAL_REVIEW);
        assertThat(partial.getAmount()).isEqualTo(10_000);
        assertThat(partial.getPayment())
                .extracting(Payment::getOrderAmount, Payment::getPaidAmount,
                        Payment::getPendingRefundAmount, Payment::getRefundedAmount, Payment::getStatus)
                .containsExactly(20_000, 20_000, 0, 10_000, PaymentStatus.PARTIALLY_REFUNDED);
        CustomerReturn customerReturn = em.find(CustomerReturn.class, partial.getSourceId());
        assertThat(customerReturn.getStatus()).isEqualTo(CustomerReturnStatus.COMPLETED);
        assertThat(customerReturn.getRmaId()).isNotNull();
        assertThat(customerReturn.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getRequestedQuantity()).isEqualTo(2);
            assertThat(item.getAcceptedQuantity()).isEqualTo(1);
            assertThat(item.getDisposition()).isEqualTo(ReturnDisposition.RESTOCKED);
        });
        assertThat(full.getSourceId()).isEqualTo(full.getPayment().getOrder().getId());
        assertThat(full.getAmount()).isEqualTo(20_000);
        assertThat(full.getPayment())
                .extracting(Payment::getPendingRefundAmount, Payment::getRefundedAmount, Payment::getStatus)
                .containsExactly(0, 20_000, PaymentStatus.REFUNDED);
        assertThat(review.getSourceId()).isEqualTo(review.getPayment().getOrder().getId());
        assertThat(review.getAmount()).isEqualTo(20_000);
        assertThat(review.getPayment())
                .extracting(Payment::getPendingRefundAmount, Payment::getRefundedAmount, Payment::getStatus)
                .containsExactly(20_000, 0, PaymentStatus.PAID);
        assertThat(refunds).allSatisfy(refund -> assertThat(refund.getAttemptCount()).isEqualTo(1));
    }

    @Test
    void Account가_없어도_기존_주문이_있으면_아무것도_시드하지_않는다() {
        Product product = new Product();
        product.setName("기존 상품");
        product.setPrice(7_000);
        em.persist(product);
        Member member = Member.createUser("기존 회원", "010-0000-0000", new Address("서울", "관악", "1"));
        em.persist(member);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악", "1"));
        Order existing = Order.createOrder(member, delivery,
                OrderItem.createOrderItem(product, 7_000, 1));
        em.persist(existing);
        em.flush();

        new initDb(new initDb.initService(em, "1111")).init();

        assertThat(em.createQuery("select count(a) from Account a", Long.class).getSingleResult()).isZero();
        assertThat(em.createQuery("select count(p) from Product p", Long.class).getSingleResult()).isEqualTo(1L);
        assertThat(orderRepository.findAll()).containsExactly(existing);
        assertThat(em.createQuery("select count(p) from Payment p", Long.class).getSingleResult()).isZero();
    }

    @Test
    void 관리자_비밀번호는_주입받은_값으로_시드된다() {
        initDb.initService service = new initDb.initService(em, "s3cret-from-env");
        initDb db = new initDb(service);

        db.init();

        Account admin = em.createQuery(
                "select a from Account a where a.email = 'admin@admin.com'", Account.class).getSingleResult();
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        assertThat(encoder.matches("s3cret-from-env", admin.getPassword())).isTrue();
        assertThat(encoder.matches("1111", admin.getPassword())).isFalse(); // 더 이상 하드코딩 1111 아님
    }

    private RefundRequest refund(RefundSourceType sourceType, RefundStatus status) {
        return em.createQuery("select r from RefundRequest r where r.sourceType = :sourceType and r.status = :status",
                        RefundRequest.class)
                .setParameter("sourceType", sourceType)
                .setParameter("status", status)
                .getSingleResult();
    }

    private Payment payment(OrderStatus orderStatus, PaymentStatus paymentStatus) {
        return em.createQuery("select p from Payment p where p.order.status = :orderStatus and p.status = :paymentStatus",
                        Payment.class)
                .setParameter("orderStatus", orderStatus)
                .setParameter("paymentStatus", paymentStatus)
                .getSingleResult();
    }
}
