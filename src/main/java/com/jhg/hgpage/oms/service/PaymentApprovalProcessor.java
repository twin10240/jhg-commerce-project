package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.contract.PaymentGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentApprovalProcessor {

    private final PaymentService paymentService;
    private final PaymentGateway paymentGateway;

    public void process(Long attemptId) {
        paymentService.claimApproval(attemptId).ifPresent(command -> {
            PaymentGateway.ApprovalResult result;
            try {
                result = paymentGateway.approve(command);
                if (result == null) {
                    result = failure("NULL_GATEWAY_RESULT", "Gateway returned no result");
                }
            } catch (RuntimeException exception) {
                result = failure("GATEWAY_ERROR", exception.getClass().getSimpleName());
            }
            paymentService.applyApprovalResult(attemptId, result);
        });
    }

    private PaymentGateway.ApprovalResult failure(String code, String reason) {
        return new PaymentGateway.ApprovalResult(PaymentGateway.GatewayOutcome.RETRYABLE_FAILURE,
                null, code, reason);
    }
}
