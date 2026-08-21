package com.jhg.hgpage;

import com.jhg.hgpage.oms.domain.*;
import com.jhg.hgpage.oms.domain.enums.RefundSourceType;
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
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class initDb {
    private final initService initService;

    @PostConstruct
    public void init() {
        // ddl-auto: update에서는 재시작해도 데이터가 남으므로, 비어 있는 DB에만 시드한다
        if (initService.alreadySeeded()) {
            return;
        }
        initService.initAccount();
        initService.initProduct();
        initService.initPaymentScenarios();
    }

    @Component
    @Transactional
    static class initService {
        private final EntityManager em;
        // 관리자 비밀번호는 코드에 박지 않는다. 운영(Railway)은 ADMIN_PASSWORD env로 주입, 로컬은 기본값 1111.
        private final String adminPassword;

        initService(EntityManager em,
                    @Value("${ADMIN_PASSWORD:1111}") String adminPassword) {
            this.em = em;
            this.adminPassword = adminPassword;
        }

        public boolean alreadySeeded() {
            Long count = em.createQuery("select count(a) from Account a", Long.class).getSingleResult();
            return count > 0;
        }

        public void initAccount() {
            Member admin = Member.createAdmin("관리자", "010-1111-2222", new Address("서울", "관악구", "500"));
            em.persist(admin);

            Account adminAccount = new Account("admin@admin.com", new BCryptPasswordEncoder(12).encode(adminPassword), admin, Role.ADMIN);
            em.persist(adminAccount);

            Member member = Member.createUser("조형근", "010-6797-5587", new Address("서울", "관악구", "500"));
            em.persist(member);

            Account account = new Account("twin10240@naver.com", new BCryptPasswordEncoder(12).encode("1111"), member, Role.USER);
            em.persist(account);
        }

        public void initProduct() {
            for (int i = 0; i < 20; i++) {
                Product product = new Product();
                product.setName("상품" + (i + 1));
                product.setPrice(10000 + (i * 1000));
                em.persist(product);
            }
        }

        public void initPaymentScenarios() {
            Member member = em.createQuery("select m from Member m where m.name = :name", Member.class)
                    .setParameter("name", "조형근")
                    .getSingleResult();
            Product product = em.createQuery("select p from Product p order by p.id", Product.class)
                    .setMaxResults(1)
                    .getSingleResult();

            failedPayment(member, product);
            reviewPayment(member, product);
            paidOrder(member, product);
            allocationReview(member, product);
            paidBackorder(member, product);
            cancelledRefundReview(member, product);
            cancellationReleaseReview(member, product);
        }

        private void failedPayment(Member member, Product product) {
            Order order = pendingOrder(member, product);
            Payment payment = payment(order);
            payment.markPaymentFailed();
            order.markPaymentFailed();
        }

        private void reviewPayment(Member member, Product product) {
            Order order = pendingOrder(member, product);
            Payment payment = payment(order);
            payment.markPaymentReview();
            order.markPaymentReview();
        }

        private void allocationReview(Member member, Product product) {
            Order order = paidOrder(member, product);
            order.claimAllocation(LocalDateTime.now());
            order.markAllocationReview("DEMO_WMS_UNAVAILABLE");
        }

        private void paidBackorder(Member member, Product product) {
            Order order = paidOrder(member, product);
            order.markBackordered();
        }

        private void cancelledRefundReview(Member member, Product product) {
            Order order = paidOrder(member, product);
            order.markBackordered();
            order.requestCancellation(false, LocalDateTime.now());
            order.finishCancellation();
            Payment payment = em.createQuery("select p from Payment p where p.order = :order", Payment.class)
                    .setParameter("order", order).getSingleResult();
            payment.reserveRefund(payment.getPaidAmount());
            RefundRequest request = RefundRequest.create(payment, UUID.randomUUID(),
                    RefundSourceType.ORDER_CANCEL, order.getId(), payment.getPaidAmount());
            em.persist(request);
            request.claim(LocalDateTime.now());
            request.manualReview("DEMO_GATEWAY_ERROR", "Reset demo refund review", LocalDateTime.now());
        }

        private void cancellationReleaseReview(Member member, Product product) {
            Order order = paidOrder(member, product);
            order.markOrdered();
            order.requestCancellation(true, LocalDateTime.now());
            order.claimCancellation(LocalDateTime.now());
            order.reviewCancellation("DEMO_WMS_UNAVAILABLE");
        }

        private Order pendingOrder(Member member, Product product) {
            Delivery delivery = new Delivery();
            delivery.setAddress(new Address("서울", "관악구", "500"));
            Order order = Order.createOrder(member, delivery,
                    OrderItem.createOrderItem(product, product.getPrice(), 1));
            order.markPaymentPending();
            em.persist(order);
            return order;
        }

        private Payment payment(Order order) {
            Payment payment = Payment.create(order, order.getTotalPrice());
            em.persist(payment);
            return payment;
        }

        private Order paidOrder(Member member, Product product) {
            Order order = pendingOrder(member, product);
            payment(order).markPaid(LocalDateTime.now());
            order.markAllocationPending();
            return order;
        }
    }
}
