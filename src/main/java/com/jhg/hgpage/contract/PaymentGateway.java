package com.jhg.hgpage.contract;

import java.util.UUID;

public interface PaymentGateway {
    ApprovalResult approve(ApprovalCommand command);

    RefundResult refund(RefundCommand command);

    record ApprovalCommand(Long orderId, int amount, UUID requestKey) {
    }

    record RefundCommand(Long paymentId, Long refundId, int amount, UUID requestKey) {
    }

    record ApprovalResult(GatewayOutcome outcome, String transactionId,
                          String failureCode, String failureReason) {
    }

    record RefundResult(GatewayOutcome outcome, String transactionId,
                        String failureCode, String failureReason) {
    }

    enum GatewayOutcome {
        SUCCESS, DECLINED, RETRYABLE_FAILURE, PERMANENT_FAILURE, UNKNOWN
    }
}
