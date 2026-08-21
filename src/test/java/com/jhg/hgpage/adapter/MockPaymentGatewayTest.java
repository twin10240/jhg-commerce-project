package com.jhg.hgpage.adapter;

import com.jhg.hgpage.contract.PaymentGateway.ApprovalCommand;
import com.jhg.hgpage.contract.PaymentGateway.ApprovalResult;
import com.jhg.hgpage.contract.PaymentGateway.GatewayOutcome;
import com.jhg.hgpage.contract.PaymentGateway.RefundCommand;
import com.jhg.hgpage.contract.PaymentGateway.RefundResult;
import com.jhg.hgpage.payment.adapter.MockPaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockPaymentGatewayTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(MockPaymentGateway.class);

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

    @Test
    void 로컬_환경설정으로_승인거절과_환불일시실패를_주입한다() {
        contextRunner.withPropertyValues(
                        "mock-payment.approval-outcome=DECLINED",
                        "mock-payment.refund-outcome=RETRYABLE_FAILURE")
                .run(context -> {
                    MockPaymentGateway gateway = context.getBean(MockPaymentGateway.class);

                    ApprovalResult approval = gateway.approve(
                            new ApprovalCommand(1L, 10_000, UUID.fromString("00000000-0000-0000-0000-000000000001")));
                    RefundResult refund = gateway.refund(
                            new RefundCommand(1L, 2L, 5_000,
                                    UUID.fromString("00000000-0000-0000-0000-000000000002")));

                    assertThat(approval.outcome()).isEqualTo(GatewayOutcome.DECLINED);
                    assertThat(approval.transactionId()).isNull();
                    assertThat(approval.failureCode()).isEqualTo("MOCK_DECLINED");
                    assertThat(refund.outcome()).isEqualTo(GatewayOutcome.RETRYABLE_FAILURE);
                    assertThat(refund.transactionId()).isNull();
                    assertThat(refund.failureCode()).isEqualTo("MOCK_RETRYABLE_FAILURE");
                });
    }

    @Test
    void 로컬_환경설정으로_환불영구실패를_주입한다() {
        contextRunner.withPropertyValues("mock-payment.refund-outcome=PERMANENT_FAILURE")
                .run(context -> {
                    RefundResult refund = context.getBean(MockPaymentGateway.class).refund(
                            new RefundCommand(1L, 2L, 5_000,
                                    UUID.fromString("00000000-0000-0000-0000-000000000003")));

                    assertThat(refund.outcome()).isEqualTo(GatewayOutcome.PERMANENT_FAILURE);
                    assertThat(refund.failureCode()).isEqualTo("MOCK_PERMANENT_FAILURE");
                });
    }
}
