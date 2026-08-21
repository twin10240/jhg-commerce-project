package com.jhg.hgpage.adapter;

import com.jhg.hgpage.contract.PaymentGateway.ApprovalCommand;
import com.jhg.hgpage.contract.PaymentGateway.ApprovalResult;
import com.jhg.hgpage.contract.PaymentGateway.GatewayOutcome;
import com.jhg.hgpage.contract.PaymentGateway.RefundCommand;
import com.jhg.hgpage.contract.PaymentGateway.RefundResult;
import com.jhg.hgpage.payment.adapter.FaultInjectingMockPaymentGateway;
import com.jhg.hgpage.payment.adapter.MockPaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockPaymentGatewayTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GatewayBeans.class);

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
    void fault_프로파일이_없으면_실패_환경설정을_무시하고_성공한다() {
        contextRunner.withPropertyValues(
                        "MOCK_PAYMENT_APPROVAL_OUTCOME=DECLINED",
                        "MOCK_PAYMENT_REFUND_OUTCOME=RETRYABLE_FAILURE")
                .run(context -> {
                    var gateway = context.getBean(com.jhg.hgpage.contract.PaymentGateway.class);
                    assertThat(gateway).isExactlyInstanceOf(MockPaymentGateway.class);

                    ApprovalResult approval = gateway.approve(
                            new ApprovalCommand(1L, 10_000, UUID.fromString("00000000-0000-0000-0000-000000000001")));
                    RefundResult refund = gateway.refund(
                            new RefundCommand(1L, 2L, 5_000,
                                    UUID.fromString("00000000-0000-0000-0000-000000000002")));

                    assertThat(approval.outcome()).isEqualTo(GatewayOutcome.SUCCESS);
                    assertThat(approval.transactionId()).isNotBlank();
                    assertThat(refund.outcome()).isEqualTo(GatewayOutcome.SUCCESS);
                    assertThat(refund.transactionId()).isNotBlank();
                });
    }

    @Test
    void fault_프로파일에서만_승인거절과_환불실패를_주입한다() {
        contextRunner.withPropertyValues(
                        "spring.profiles.active=payment-faults",
                        "MOCK_PAYMENT_APPROVAL_OUTCOME=DECLINED",
                        "MOCK_PAYMENT_REFUND_OUTCOME=PERMANENT_FAILURE")
                .run(context -> {
                    var gateway = context.getBean(com.jhg.hgpage.contract.PaymentGateway.class);
                    assertThat(gateway).isExactlyInstanceOf(FaultInjectingMockPaymentGateway.class);
                    ApprovalResult approval = gateway.approve(
                            new ApprovalCommand(1L, 10_000,
                                    UUID.fromString("00000000-0000-0000-0000-000000000003")));
                    RefundResult refund = gateway.refund(
                            new RefundCommand(1L, 2L, 5_000,
                                    UUID.fromString("00000000-0000-0000-0000-000000000004")));

                    assertThat(approval.outcome()).isEqualTo(GatewayOutcome.DECLINED);
                    assertThat(approval.failureCode()).isEqualTo("MOCK_DECLINED");
                    assertThat(refund.outcome()).isEqualTo(GatewayOutcome.PERMANENT_FAILURE);
                    assertThat(refund.failureCode()).isEqualTo("MOCK_PERMANENT_FAILURE");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = MockPaymentGateway.class)
    static class GatewayBeans {
    }
}
