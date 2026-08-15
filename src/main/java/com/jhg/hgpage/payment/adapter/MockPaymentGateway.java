package com.jhg.hgpage.payment.adapter;

import com.jhg.hgpage.contract.PaymentGateway;
import org.springframework.stereotype.Component;

@Component
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public ApprovalResult approve(ApprovalCommand command) {
        if (command.amount() <= 0) {
            return new ApprovalResult(GatewayOutcome.PERMANENT_FAILURE, null,
                    "INVALID_AMOUNT", "Amount must be positive");
        }
        return new ApprovalResult(GatewayOutcome.SUCCESS, "MOCK-PAY-" + command.requestKey(), null, null);
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        if (command.amount() <= 0) {
            return new RefundResult(GatewayOutcome.PERMANENT_FAILURE, null,
                    "INVALID_AMOUNT", "Amount must be positive");
        }
        return new RefundResult(GatewayOutcome.SUCCESS, "MOCK-REFUND-" + command.requestKey(), null, null);
    }
}
