package com.jhg.hgpage;

import com.jhg.hgpage.oms.domain.*;
import com.jhg.hgpage.oms.domain.enums.RefundSourceType;
import com.jhg.hgpage.oms.domain.enums.ReturnDisposition;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.domain.enums.Role;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class initDb {
    private final initService initService;

    @PostConstruct
    public void init() {
        initService.initIfEmpty();
    }

    @Component
    @Transactional
    static class initService {
        private static final int BACKORDER_QUANTITY = 16; // WMS 로컬 초기 가용수량 15보다 커야 한다.

        private final EntityManager em;
        // 관리자 비밀번호는 코드에 박지 않는다. 운영(Railway)은 ADMIN_PASSWORD env로 주입, 로컬은 기본값 1111.
        private final String adminPassword;

        initService(EntityManager em,
                    @Value("${ADMIN_PASSWORD:1111}") String adminPassword) {
            this.em = em;
            this.adminPassword = adminPassword;
        }

        public void initIfEmpty() {
            if (hasBusinessData()) {
                return;
            }
            initAccount();
            initProduct();
            initPaymentScenarios();
        }

        private boolean hasBusinessData() {
            return count(Account.class) + count(Member.class) + count(Product.class) + count(Order.class)
                    + count(Payment.class) + count(PaymentAttempt.class) + count(RefundRequest.class)
                    + count(CustomerReturn.class) > 0;
        }

        private long count(Class<?> entityType) {
            return em.createQuery("select count(e) from " + entityType.getSimpleName() + " e", Long.class)
                    .getSingleResult();
        }

        private void initAccount() {
            Member admin = Member.createAdmin("관리자", "010-1111-2222", new Address("서울", "관악구", "500"));
            em.persist(admin);

            Account adminAccount = new Account("admin@admin.com", new BCryptPasswordEncoder(12).encode(adminPassword), admin, Role.ADMIN);
            em.persist(adminAccount);

            Member member = Member.createUser("조형근", "010-6797-5587", new Address("서울", "관악구", "500"));
            em.persist(member);

            Account account = new Account("twin10240@naver.com", new BCryptPasswordEncoder(12).encode("1111"), member, Role.USER);
            em.persist(account);
        }

        private void initProduct() {
            for (int i = 0; i < 20; i++) {
                Product product = new Product();
                product.setName("상품" + (i + 1));
                product.setPrice(10000 + (i * 1000));
                em.persist(product);
            }
        }

        private void initPaymentScenarios() {
            Member member = em.createQuery("select m from Member m where m.name = :name", Member.class)
                    .setParameter("name", "조형근")
                    .getSingleResult();
            Product product = em.createQuery("select p from Product p order by p.id", Product.class)
                    .setMaxResults(1)
                    .getSingleResult();

            paidOrderState(member, product);
            paidBackorder(member, product);
            failedPayment(member, product);
            partialRefund(member, product);
            fullRefund(member, product);
            refundReview(member, product);
            allocationReview(member, product);
        }

        private void paidOrderState(Member member, Product product) {
            completeAllocation(paidOrder(member, product, 1), true);
        }

        private void paidBackorder(Member member, Product product) {
            completeAllocation(paidOrder(member, product, BACKORDER_QUANTITY), false);
        }

        private void failedPayment(Member member, Product product) {
            PaymentFixture fixture = pendingPayment(member, product, 1);
            LocalDateTime now = LocalDateTime.now();
            fixture.attempt().claim(now);
            fixture.attempt().fail("SEED_DECLINED", "Reset demo declined payment", now);
            Payment payment = fixture.payment();
            payment.markPaymentFailed();
            fixture.order().markPaymentFailed();
        }

        private void partialRefund(Member member, Product product) {
            PaymentFixture fixture = paidOrder(member, product, 2);
            completeAllocation(fixture, true);
            fixture.order().ship();
            fixture.order().deliver();

            CustomerReturn customerReturn = CustomerReturn.create(fixture.order(), UUID.randomUUID(),
                    "Reset demo partial return", List.of(
                            new CustomerReturn.RequestItem(fixture.order().getOrderItems().get(0), 2)));
            em.persist(customerReturn);
            customerReturn.approve("admin@example.com");
            customerReturn.markRequested(100_000L + customerReturn.getId());
            customerReturn.complete(List.of(new CustomerReturn.ResultItem(
                    fixture.order().getOrderItems().get(0).getId(), 1, ReturnDisposition.RESTOCKED)));
            completeRefund(fixture.payment(), RefundSourceType.RETURN, customerReturn.getId(), 10_000);
        }

        private void fullRefund(Member member, Product product) {
            PaymentFixture fixture = paidOrder(member, product, 2);
            completeAllocation(fixture, false);
            cancelWithoutRelease(fixture.order());
            completeRefund(fixture.payment(), RefundSourceType.ORDER_CANCEL,
                    fixture.order().getId(), 20_000);
        }

        private void refundReview(Member member, Product product) {
            PaymentFixture fixture = paidOrder(member, product, 2);
            completeAllocation(fixture, false);
            cancelWithoutRelease(fixture.order());
            fixture.payment().reserveRefund(20_000);
            RefundRequest request = RefundRequest.create(fixture.payment(), UUID.randomUUID(),
                    RefundSourceType.ORDER_CANCEL, fixture.order().getId(), 20_000);
            em.persist(request);
            request.claim(LocalDateTime.now());
            request.manualReview("SEED_GATEWAY_ERROR", "Reset demo refund review", LocalDateTime.now());
        }

        private void allocationReview(Member member, Product product) {
            Order order = paidOrder(member, product, 1).order();
            order.claimAllocation(LocalDateTime.now());
            order.markAllocationReview("SEED_WMS_UNAVAILABLE");
        }

        private void cancelWithoutRelease(Order order) {
            order.requestCancellation(false, LocalDateTime.now());
            order.finishCancellation();
        }

        private void completeRefund(Payment payment, RefundSourceType sourceType, Long sourceId, int amount) {
            payment.reserveRefund(amount);
            RefundRequest request = RefundRequest.create(payment, UUID.randomUUID(),
                    sourceType, sourceId, amount);
            em.persist(request);
            request.claim(LocalDateTime.now());
            request.succeed("MOCK-REFUND-DEMO", LocalDateTime.now());
            payment.completeRefund(amount);
        }

        private PaymentFixture pendingPayment(Member member, Product product, int count) {
            Delivery delivery = new Delivery();
            delivery.setAddress(new Address("서울", "관악구", "500"));
            Order order = Order.createOrder(member, delivery,
                    OrderItem.createOrderItem(product, product.getPrice(), count));
            order.markPaymentPending();
            em.persist(order);
            Payment payment = Payment.create(order, order.getTotalPrice());
            em.persist(payment);
            PaymentAttempt attempt = PaymentAttempt.create(payment, UUID.randomUUID());
            em.persist(attempt);
            return new PaymentFixture(order, payment, attempt);
        }

        private PaymentFixture paidOrder(Member member, Product product, int count) {
            PaymentFixture fixture = pendingPayment(member, product, count);
            LocalDateTime now = LocalDateTime.now();
            fixture.attempt().claim(now);
            fixture.attempt().succeed("SEED-PAY-" + fixture.attempt().getRequestKey(), now);
            fixture.payment().markPaid(now);
            fixture.order().markAllocationPending();
            return fixture;
        }

        private void completeAllocation(PaymentFixture fixture, boolean reserved) {
            fixture.order().claimAllocation(LocalDateTime.now());
            fixture.order().completeAllocation(reserved);
        }

        private record PaymentFixture(Order order, Payment payment, PaymentAttempt attempt) {}
    }
}
