package com.jhg.hgpage.domain;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.enums.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

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
