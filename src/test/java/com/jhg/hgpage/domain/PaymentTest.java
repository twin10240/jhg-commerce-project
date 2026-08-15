package com.jhg.hgpage.domain;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.PaymentAttempt;
import com.jhg.hgpage.oms.domain.enums.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    @Test
    void 환불_예약과_완료는_승인금액을_넘을_수_없다() {
        Payment payment = paidPayment(30_000);

        payment.reserveRefund(20_000);
        assertThatThrownBy(() -> payment.reserveRefund(10_001))
                .isInstanceOf(IllegalStateException.class);

        payment.completeRefund(20_000);

        assertThat(payment.getPendingRefundAmount()).isZero();
        assertThat(payment.getRefundedAmount()).isEqualTo(20_000);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    void 전액_환불을_완료하면_환불완료가_된다() {
        Payment payment = paidPayment(30_000);

        payment.reserveRefund(30_000);
        payment.completeRefund(30_000);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void 결제실패_주문은_새_시도로_다시_결제해_할당대기로_전환한다() {
        Order order = order();
        Payment payment = Payment.create(order, 30_000);
        order.markPaymentPending();
        PaymentAttempt failedAttempt = PaymentAttempt.create(payment, UUID.randomUUID());
        failedAttempt.claim(LocalDateTime.now());
        failedAttempt.fail("DECLINED", "declined", LocalDateTime.now());
        payment.markPaymentFailed();
        order.markPaymentFailed();

        PaymentAttempt retryAttempt = PaymentAttempt.create(payment, UUID.randomUUID());
        assertThat(retryAttempt.getRequestKey()).isNotEqualTo(failedAttempt.getRequestKey());
        payment.retry();
        order.markPaymentPending();
        retryAttempt.claim(LocalDateTime.now());
        retryAttempt.succeed("MOCK-PAY-RETRY", LocalDateTime.now());
        payment.markPaid(LocalDateTime.now());
        order.markAllocationPending();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(order.getStatus()).isEqualTo(com.jhg.hgpage.oms.domain.enums.OrderStatus.ALLOCATION_PENDING);
    }

    private Payment paidPayment(int amount) {
        Payment payment = Payment.create(order(), amount);
        payment.markPaid(LocalDateTime.now());
        return payment;
    }

    private Order order() {
        Product product = new Product();
        product.setPrice(10_000);
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        return Order.createOrder(member, delivery, OrderItem.createOrderItem(product, 10_000, 3));
    }
}
