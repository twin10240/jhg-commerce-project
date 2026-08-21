package com.jhg.hgpage.payment.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("payment-faults")
public class FaultInjectingMockPaymentGateway extends MockPaymentGateway {

    public FaultInjectingMockPaymentGateway(
            @Value("${MOCK_PAYMENT_APPROVAL_OUTCOME:SUCCESS}") GatewayOutcome approvalOutcome,
            @Value("${MOCK_PAYMENT_REFUND_OUTCOME:SUCCESS}") GatewayOutcome refundOutcome) {
        super(approvalOutcome, refundOutcome);
    }
}
