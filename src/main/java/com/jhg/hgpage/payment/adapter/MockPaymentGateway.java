package com.jhg.hgpage.payment.adapter;

import com.jhg.hgpage.contract.PaymentGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MockPaymentGateway implements PaymentGateway {

    private final GatewayOutcome approvalOutcome;
    private final GatewayOutcome refundOutcome;

    public MockPaymentGateway() {
        this(GatewayOutcome.SUCCESS, GatewayOutcome.SUCCESS);
    }

    @Autowired
    public MockPaymentGateway(
            @Value("${mock-payment.approval-outcome:SUCCESS}") GatewayOutcome approvalOutcome,
            @Value("${mock-payment.refund-outcome:SUCCESS}") GatewayOutcome refundOutcome) {
        this.approvalOutcome = approvalOutcome;
        this.refundOutcome = refundOutcome;
    }

    @Override
    public ApprovalResult approve(ApprovalCommand command) {
        if (command.amount() <= 0) {
            return new ApprovalResult(GatewayOutcome.PERMANENT_FAILURE, null,
                    "INVALID_AMOUNT", "Amount must be positive");
        }
        if (approvalOutcome != GatewayOutcome.SUCCESS) {
            return new ApprovalResult(approvalOutcome, null,
                    "MOCK_" + approvalOutcome, "Configured mock approval outcome");
        }
        return new ApprovalResult(GatewayOutcome.SUCCESS, "MOCK-PAY-" + command.requestKey(), null, null);
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        if (command.amount() <= 0) {
            return new RefundResult(GatewayOutcome.PERMANENT_FAILURE, null,
                    "INVALID_AMOUNT", "Amount must be positive");
        }
        if (refundOutcome != GatewayOutcome.SUCCESS) {
            return new RefundResult(refundOutcome, null,
                    "MOCK_" + refundOutcome, "Configured mock refund outcome");
        }
        return new RefundResult(GatewayOutcome.SUCCESS, "MOCK-REFUND-" + command.requestKey(), null, null);
    }
}
