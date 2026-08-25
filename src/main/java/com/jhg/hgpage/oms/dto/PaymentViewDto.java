package com.jhg.hgpage.oms.dto;

import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;

public record PaymentViewDto(
        String orderStatusLabel,
        String paymentStatusLabel,
        int paidAmount,
        int pendingRefundAmount,
        int refundedAmount,
        boolean paymentRetryable,
        String refundStatusLabel) {

    public static PaymentViewDto from(Order order, Payment payment) {
        if (payment == null) {
            return new PaymentViewDto(orderStatusLabel(order, null), "결제 이력 없음",
                    0, 0, 0, false, "환불 내역 없음");
        }
        return new PaymentViewDto(
                orderStatusLabel(order, payment),
                switch (payment.getStatus()) {
                    case PENDING -> "결제 대기";
                    case PAYMENT_REVIEW -> "결제 확인 중";
                    case PAYMENT_FAILED -> "결제 실패";
                    case PAID, PARTIALLY_REFUNDED, REFUNDED -> "결제 완료";
                    case CANCELLED -> "결제 취소";
                },
                payment.getPaidAmount(),
                payment.getPendingRefundAmount(),
                payment.getRefundedAmount(),
                order.getStatus() == OrderStatus.PAYMENT_FAILED,
                payment.getPendingRefundAmount() > 0 ? "환불 확인 중"
                        : payment.getRefundedAmount() == 0 ? "환불 내역 없음"
                        : payment.getRefundedAmount() == payment.getPaidAmount() ? "환불 완료" : "부분 환불 완료");
    }

    public static PaymentViewDto legacy(OrderStatus status, DeliveryStatus deliveryStatus) {
        String label = switch (status) {
            case PAYMENT_PENDING -> "결제 대기";
            case PAYMENT_REVIEW -> "결제 확인 중";
            case PAYMENT_FAILED -> "결제 실패";
            case ALLOCATION_PENDING, ALLOCATION_PROCESSING -> "재고 확인 중";
            case ALLOCATION_REVIEW -> "재고 확인 지연";
            case CANCEL_REQUESTED -> "주문 취소 처리 중";
            case BACKORDERED -> "입고 대기";
            case CANCEL -> "주문 취소";
            case ORDER -> deliveryLabel(deliveryStatus);
        };
        return new PaymentViewDto(label, "결제 이력 없음", 0, 0, 0, false, "환불 내역 없음");
    }

    private static String orderStatusLabel(Order order, Payment payment) {
        return switch (order.getStatus()) {
            case PAYMENT_PENDING -> "결제 대기";
            case PAYMENT_REVIEW -> "결제 확인 중";
            case PAYMENT_FAILED -> "결제 실패";
            case ALLOCATION_PENDING, ALLOCATION_PROCESSING -> "재고 확인 중";
            case ALLOCATION_REVIEW -> "재고 확인 지연";
            case CANCEL_REQUESTED -> "주문 취소 처리 중";
            case BACKORDERED -> payment != null && payment.getPaidAmount() > 0
                    ? "결제 완료 · 입고 대기" : "입고 대기";
            case CANCEL -> "주문 취소";
            case ORDER -> deliveryLabel(order.getDelivery().getStatus());
        };
    }

    private static String deliveryLabel(DeliveryStatus status) {
        return switch (status) {
            case READY -> "재고 확보";
            case SHIPPED -> "출고 완료";
            case DELIVERED -> "배송 완료";
        };
    }
}
