package com.jhg.hgpage.adapter;

import com.jhg.hgpage.contract.PaymentGateway.ApprovalCommand;
import com.jhg.hgpage.contract.PaymentGateway.ApprovalResult;
import com.jhg.hgpage.contract.PaymentGateway.GatewayOutcome;
import com.jhg.hgpage.contract.PaymentGateway.RefundCommand;
import com.jhg.hgpage.contract.PaymentGateway.RefundResult;
import com.jhg.hgpage.payment.adapter.MockPaymentGateway;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockPaymentGatewayTest {

    @Test
    void 기본_모의_승인과_환불은_성공하고_거래번호를_반환한다() {
        MockPaymentGateway gateway = new MockPaymentGateway();

        ApprovalResult approval = gateway.approve(new ApprovalCommand(1L, 10_000, UUID.randomUUID()));
        RefundResult refund = gateway.refund(new RefundCommand(1L, 1L, 5_000, UUID.randomUUID()));

        assertThat(approval.outcome()).isEqualTo(GatewayOutcome.SUCCESS);
        assertThat(approval.transactionId()).isNotBlank();
        assertThat(refund.outcome()).isEqualTo(GatewayOutcome.SUCCESS);
        assertThat(refund.transactionId()).isNotBlank();
    }

    @Test
    void 양수가_아닌_금액은_영구_실패를_반환한다() {
        MockPaymentGateway gateway = new MockPaymentGateway();

        ApprovalResult approval = gateway.approve(new ApprovalCommand(1L, 0, UUID.randomUUID()));
        RefundResult refund = gateway.refund(new RefundCommand(1L, -1L, 0, UUID.randomUUID()));

        assertThat(approval.outcome()).isEqualTo(GatewayOutcome.PERMANENT_FAILURE);
        assertThat(refund.outcome()).isEqualTo(GatewayOutcome.PERMANENT_FAILURE);
    }
}
