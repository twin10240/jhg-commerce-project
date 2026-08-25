package com.jhg.hgpage.dto;

import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.domain.enums.PaymentStatus;
import com.jhg.hgpage.oms.dto.PaymentViewDto;
import com.jhg.hgpage.catalog.Product;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentViewDtoTest {

    @ParameterizedTest
    @MethodSource("processingLabels")
    void 처리중_주문상태를_고객용_한국어로_표시한다(OrderStatus status, String label) {
        Order order = order(status);

        assertThat(PaymentViewDto.from(order, payment(order, paymentStatus(status))).orderStatusLabel())
                .isEqualTo(label);
    }

    static Stream<Arguments> processingLabels() {
        return Stream.of(
                Arguments.of(OrderStatus.PAYMENT_PENDING, "결제 대기"),
                Arguments.of(OrderStatus.PAYMENT_REVIEW, "결제 확인 중"),
                Arguments.of(OrderStatus.PAYMENT_FAILED, "결제 실패"),
                Arguments.of(OrderStatus.ALLOCATION_PENDING, "재고 확인 중"),
                Arguments.of(OrderStatus.ALLOCATION_PROCESSING, "재고 확인 중"),
                Arguments.of(OrderStatus.ALLOCATION_REVIEW, "재고 확인 지연"),
                Arguments.of(OrderStatus.CANCEL_REQUESTED, "주문 취소 처리 중"));
    }

    @Test
    void 결제된_백오더는_결제완료와_입고대기를_함께_표시한다() {
        Order order = order(OrderStatus.BACKORDERED);

        assertThat(PaymentViewDto.from(order, payment(order, PaymentStatus.PAID)).orderStatusLabel())
                .isEqualTo("결제 완료 · 입고 대기");
    }

    @Test
    void 처리중인_환불은_내부상태와_무관하게_환불확인중으로_표시한다() {
        Order order = order(OrderStatus.ORDER);
        Payment payment = payment(order, PaymentStatus.PAID);
        ReflectionTestUtils.setField(payment, "pendingRefundAmount", 4_000);

        PaymentViewDto view = PaymentViewDto.from(order, payment);

        assertThat(view.refundStatusLabel()).isEqualTo("환불 확인 중");
        assertThat(view.pendingRefundAmount()).isEqualTo(4_000);
    }

    @Test
    void 결제실패_주문만_재결제할_수_있다() {
        Order failed = order(OrderStatus.PAYMENT_FAILED);
        Order pending = order(OrderStatus.PAYMENT_PENDING);

        assertThat(PaymentViewDto.from(failed, payment(failed, PaymentStatus.PAYMENT_FAILED)).paymentRetryable())
                .isTrue();
        assertThat(PaymentViewDto.from(pending, payment(pending, PaymentStatus.PENDING)).paymentRetryable())
                .isFalse();
    }

    @Test
    void 기존주문은_결제이력이_없다고_표시한다() {
        assertThat(PaymentViewDto.from(order(OrderStatus.ORDER), null).paymentStatusLabel())
                .isEqualTo("결제 이력 없음");
    }

    private PaymentStatus paymentStatus(OrderStatus status) {
        return switch (status) {
            case PAYMENT_FAILED -> PaymentStatus.PAYMENT_FAILED;
            case PAYMENT_REVIEW -> PaymentStatus.PAYMENT_REVIEW;
            case ALLOCATION_PENDING, ALLOCATION_PROCESSING, ALLOCATION_REVIEW, CANCEL_REQUESTED,
                    ORDER, BACKORDERED -> PaymentStatus.PAID;
            default -> PaymentStatus.PENDING;
        };
    }

    private Payment payment(Order order, PaymentStatus status) {
        Payment payment = Payment.create(order, order.getTotalPrice());
        ReflectionTestUtils.setField(payment, "status", status);
        if (status == PaymentStatus.PAID) {
            ReflectionTestUtils.setField(payment, "paidAmount", order.getTotalPrice());
        }
        return payment;
    }

    private Order order(OrderStatus status) {
        Product product = new Product();
        product.setName("상품");
        product.setPrice(10_000);
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery,
                OrderItem.createOrderItem(product, product.getPrice(), 1));
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }
}
