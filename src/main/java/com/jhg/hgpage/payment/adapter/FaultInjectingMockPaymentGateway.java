package com.jhg.hgpage.payment.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Profile("payment-faults")
public class FaultInjectingMockPaymentGateway extends MockPaymentGateway {

    private final AtomicBoolean declineFirstApproval;

    public FaultInjectingMockPaymentGateway(
            @Value("${MOCK_PAYMENT_APPROVAL_OUTCOME:SUCCESS}") GatewayOutcome approvalOutcome,
            @Value("${MOCK_PAYMENT_REFUND_OUTCOME:SUCCESS}") GatewayOutcome refundOutcome,
            @Value("${MOCK_PAYMENT_DECLINE_FIRST:false}") boolean declineFirstApproval) {
        super(approvalOutcome, refundOutcome);
        this.declineFirstApproval = new AtomicBoolean(declineFirstApproval);
    }

    @Override
    public ApprovalResult approve(ApprovalCommand command) {
        if (declineFirstApproval.compareAndSet(true, false)) {
            return new ApprovalResult(GatewayOutcome.DECLINED, null,
                    "MOCK_DECLINED", "첫 결제 승인 장애를 주입했습니다.");
        }
        return super.approve(command);
    }
}
