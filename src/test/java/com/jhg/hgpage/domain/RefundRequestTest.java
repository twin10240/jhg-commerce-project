package com.jhg.hgpage.domain;

import com.jhg.hgpage.oms.domain.RefundRequest;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.enums.RefundSourceType;
import com.jhg.hgpage.oms.domain.enums.RefundStatus;
import com.jhg.hgpage.catalog.Product;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RefundRequestTest {

    @Test
    void 환불요청은_선점과_재시도_상태를_기록한다() {
        RefundRequest request = RefundRequest.create(payment(), UUID.randomUUID(), RefundSourceType.RETURN, 3L, 10_000);
        LocalDateTime now = LocalDateTime.now();

        request.claim(now);
        request.retryAt(now.plusMinutes(1), "TIMEOUT", "timeout", now);

        assertThat(request.getStatus()).isEqualTo(RefundStatus.RETRYING);
        assertThat(request.getAttemptCount()).isEqualTo(1);
        assertThat(request.getNextAttemptAt()).isEqualTo(now.plusMinutes(1));
    }

    @Test
    void 환불성공은_처리중_상태에서만_가능하다() {
        RefundRequest request = RefundRequest.create(payment(), UUID.randomUUID(), RefundSourceType.ORDER_CANCEL, 1L, 10_000);
        LocalDateTime now = LocalDateTime.now();

        request.claim(now);
        request.succeed("MOCK-REFUND-1", now.plusSeconds(1));

        assertThat(request.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(request.getGatewayTransactionId()).isEqualTo("MOCK-REFUND-1");
        assertThat(request.getCompletedAt()).isEqualTo(now.plusSeconds(1));
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
