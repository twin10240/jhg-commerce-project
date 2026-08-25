package com.jhg.hgpage.dto;

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
import com.jhg.hgpage.oms.dto.AdminPaymentDto;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPaymentDtoTest {

    @Test
    void 결제행은_최신시도의_운영정보와_취소검토_동작을_담는다() {
        Fixture fixture = fixture();
        PaymentAttempt attempt = PaymentAttempt.create(fixture.payment, UUID.randomUUID());
        ReflectionTestUtils.setField(attempt, "id", 30L);
        attempt.claim(LocalDateTime.now());
        attempt.manualReview("UNKNOWN", "승인 결과 확인 필요", LocalDateTime.now());

        AdminPaymentDto row = AdminPaymentDto.payment(fixture.payment, attempt, true);

        assertThat(row.orderId()).isEqualTo(10L);
        assertThat(row.requestKey()).isEqualTo(attempt.getRequestKey().toString());
        assertThat(row.attemptCount()).isEqualTo(1);
        assertThat(row.failureReason()).isEqualTo("승인 결과 확인 필요");
        assertThat(row.retryable()).isTrue();
    }

    @Test
    void 반품환불행은_반품번호와_금액_멱등키를_담는다() {
        Fixture fixture = fixture();
        RefundRequest request = RefundRequest.create(fixture.payment, UUID.randomUUID(),
                RefundSourceType.RETURN, 40L, 5_000);
        ReflectionTestUtils.setField(request, "id", 50L);
        request.claim(LocalDateTime.now());
        request.manualReview("INVALID_AMOUNT", "금액 확인 필요", LocalDateTime.now());

        AdminPaymentDto row = AdminPaymentDto.refund(request);

        assertThat(row.orderId()).isEqualTo(10L);
        assertThat(row.returnId()).isEqualTo(40L);
        assertThat(row.amount()).isEqualTo(5_000);
        assertThat(row.requestKey()).isEqualTo(request.getRequestKey().toString());
        assertThat(row.failureReason()).isEqualTo("금액 확인 필요");
        assertThat(row.retryable()).isTrue();
    }

    @Test
    void 완료된_환불행은_게이트웨이_거래번호를_담는다() {
        Fixture fixture = fixture();
        RefundRequest request = RefundRequest.create(fixture.payment, UUID.randomUUID(),
                RefundSourceType.ORDER_CANCEL, 10L, 10_000);
        request.claim(LocalDateTime.now());
        request.succeed("MOCK-REFUND-1", LocalDateTime.now());

        AdminPaymentDto row = AdminPaymentDto.refund(request);

        assertThat(row.gatewayTransactionId()).isEqualTo("MOCK-REFUND-1");
    }

    private Fixture fixture() {
        Product product = new Product();
        product.setName("상품");
        product.setPrice(10_000);
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery,
                OrderItem.createOrderItem(product, product.getPrice(), 1));
        ReflectionTestUtils.setField(order, "id", 10L);
        order.markPaymentPending();
        Payment payment = Payment.create(order, order.getTotalPrice());
        ReflectionTestUtils.setField(payment, "id", 20L);
        return new Fixture(payment);
    }

    private record Fixture(Payment payment) {}
}
