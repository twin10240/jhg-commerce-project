package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.contract.PaymentGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RefundProcessor {

    private final RefundService refundService;
    private final PaymentGateway paymentGateway;

    public void process(Long refundId) {
        refundService.claim(refundId).ifPresent(claim -> {
            PaymentGateway.RefundResult result;
            try {
                result = paymentGateway.refund(claim.command());
                if (result == null) {
                    result = failure("NULL_GATEWAY_RESULT", "Gateway returned no result");
                }
            } catch (RuntimeException exception) {
                result = failure("GATEWAY_ERROR", exception.getClass().getSimpleName());
            }
            refundService.applyResult(refundId, claim.attemptNumber(), result);
        });
    }

    private PaymentGateway.RefundResult failure(String code, String reason) {
        return new PaymentGateway.RefundResult(
                PaymentGateway.GatewayOutcome.RETRYABLE_FAILURE, null, code, reason);
    }
}
