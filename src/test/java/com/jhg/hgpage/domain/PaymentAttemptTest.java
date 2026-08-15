package com.jhg.hgpage.domain;

import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.PaymentAttempt;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.enums.PaymentAttemptStatus;
import com.jhg.hgpage.catalog.Product;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentAttemptTest {

    @Test
    void 결제시도는_선점후에만_성공으로_완료할_수_있다() {
        PaymentAttempt attempt = PaymentAttempt.create(payment(), UUID.randomUUID());
        LocalDateTime now = LocalDateTime.now();

        assertThatThrownBy(() -> attempt.succeed("MOCK-PAY-1", now))
                .isInstanceOf(IllegalStateException.class);

        attempt.claim(now);
        attempt.succeed("MOCK-PAY-1", now.plusSeconds(1));

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(attempt.getAttemptCount()).isEqualTo(1);
        assertThat(attempt.getGatewayTransactionId()).isEqualTo("MOCK-PAY-1");
    }

    @Test
    void 재시도는_같은_시도를_대기상태로_되돌린다() {
        PaymentAttempt attempt = PaymentAttempt.create(payment(), UUID.randomUUID());
        LocalDateTime now = LocalDateTime.now();
        attempt.claim(now);

        attempt.retryAt(now.plusMinutes(1), "TIMEOUT", "timeout");

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(attempt.getNextAttemptAt()).isEqualTo(now.plusMinutes(1));
    }

    private Payment payment() {
        Product product = new Product();
        product.setPrice(10_000);
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery, OrderItem.createOrderItem(product, 10_000, 1));
        return Payment.create(order, 10_000);
    }
}
