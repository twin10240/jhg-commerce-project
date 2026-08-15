package com.jhg.hgpage.service;

import com.jhg.hgpage.contract.PaymentGateway;
import com.jhg.hgpage.oms.service.RefundProcessor;
import com.jhg.hgpage.oms.service.RefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static com.jhg.hgpage.contract.PaymentGateway.GatewayOutcome.RETRYABLE_FAILURE;
import static com.jhg.hgpage.contract.PaymentGateway.GatewayOutcome.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundProcessorTest {

    @Mock RefundService refundService;
    @Mock PaymentGateway gateway;

    RefundProcessor processor;
    PaymentGateway.RefundCommand command;

    @BeforeEach
    void setUp() {
        processor = new RefundProcessor(refundService, gateway);
        command = new PaymentGateway.RefundCommand(20L, 30L, 10_000, UUID.randomUUID());
    }

    @Test
    void claim_게이트웨이_결과적용을_세단계로_수행한다() {
        RefundService.RefundClaim claim = new RefundService.RefundClaim(2, command);
        PaymentGateway.RefundResult result = new PaymentGateway.RefundResult(
                SUCCESS, "MOCK-REFUND-1", null, null);
        when(refundService.claim(30L)).thenReturn(Optional.of(claim));
        when(gateway.refund(command)).thenReturn(result);

        processor.process(30L);

        InOrder calls = inOrder(refundService, gateway);
        calls.verify(refundService).claim(30L);
        calls.verify(gateway).refund(command);
        calls.verify(refundService).applyResult(30L, 2, result);
    }

    @Test
    void 게이트웨이_예외는_재시도_가능_실패로_기록한다() {
        RefundService.RefundClaim claim = new RefundService.RefundClaim(1, command);
        when(refundService.claim(30L)).thenReturn(Optional.of(claim));
        when(gateway.refund(command)).thenThrow(new IllegalStateException("down"));

        processor.process(30L);

        ArgumentCaptor<PaymentGateway.RefundResult> failure =
                ArgumentCaptor.forClass(PaymentGateway.RefundResult.class);
        verify(refundService).applyResult(org.mockito.ArgumentMatchers.eq(30L),
                org.mockito.ArgumentMatchers.eq(1), failure.capture());
        assertThat(failure.getValue().outcome()).isEqualTo(RETRYABLE_FAILURE);
        assertThat(failure.getValue().failureCode()).isEqualTo("GATEWAY_ERROR");
    }

    @Test
    void null_게이트웨이_결과도_재시도_가능_실패로_기록한다() {
        RefundService.RefundClaim claim = new RefundService.RefundClaim(1, command);
        when(refundService.claim(30L)).thenReturn(Optional.of(claim));
        when(gateway.refund(command)).thenReturn(null);

        processor.process(30L);

        ArgumentCaptor<PaymentGateway.RefundResult> failure =
                ArgumentCaptor.forClass(PaymentGateway.RefundResult.class);
        verify(refundService).applyResult(org.mockito.ArgumentMatchers.eq(30L),
                org.mockito.ArgumentMatchers.eq(1), failure.capture());
        assertThat(failure.getValue().outcome()).isEqualTo(RETRYABLE_FAILURE);
        assertThat(failure.getValue().failureCode()).isEqualTo("NULL_GATEWAY_RESULT");
    }
}
