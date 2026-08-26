package com.jhg.hgpage.oms.dto;

import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.PaymentAttempt;
import com.jhg.hgpage.oms.domain.RefundRequest;
import com.jhg.hgpage.oms.domain.enums.RefundSourceType;

import java.time.LocalDateTime;

public record AdminPaymentDto(
        WorkType type,
        Long id,
        Long orderId,
        Long returnId,
        String status,
        String statusLabel,
        int amount,
        int paidAmount,
        int pendingRefundAmount,
        int refundedAmount,
        String requestKey,
        String gatewayTransactionId,
        int attemptCount,
        String failureReason,
        LocalDateTime nextRetryAt,
        boolean retryable) {

    public static AdminPaymentDto payment(Payment payment, PaymentAttempt attempt, boolean retryable) {
        return new AdminPaymentDto(
                WorkType.PAYMENT,
                attempt == null ? null : attempt.getId(),
                payment.getOrder().getId(),
                null,
                payment.getStatus().name(),
                payment.getStatus().getLabel(),
                payment.getOrderAmount(),
                payment.getPaidAmount(),
                payment.getPendingRefundAmount(),
                payment.getRefundedAmount(),
                attempt == null ? null : attempt.getRequestKey().toString(),
                attempt == null ? null : attempt.getGatewayTransactionId(),
                attempt == null ? 0 : attempt.getAttemptCount(),
                attempt == null ? null : attempt.getFailureReason(),
                attempt == null ? null : attempt.getNextAttemptAt(),
                retryable);
    }

    public static AdminPaymentDto refund(RefundRequest request) {
        Payment payment = request.getPayment();
        return new AdminPaymentDto(
                WorkType.REFUND,
                request.getId(),
                payment.getOrder().getId(),
                request.getSourceType() == RefundSourceType.RETURN ? request.getSourceId() : null,
                request.getStatus().name(),
                request.getStatus().getLabel(),
                request.getAmount(),
                payment.getPaidAmount(),
                payment.getPendingRefundAmount(),
                payment.getRefundedAmount(),
                request.getRequestKey().toString(),
                request.getGatewayTransactionId(),
                request.getAttemptCount(),
                request.getLastFailureReason(),
                request.getNextAttemptAt(),
                request.getStatus() == com.jhg.hgpage.oms.domain.enums.RefundStatus.MANUAL_REVIEW);
    }

    public enum WorkType {
        PAYMENT, REFUND
    }
}
