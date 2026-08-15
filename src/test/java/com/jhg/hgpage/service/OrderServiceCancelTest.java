package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.service.CheckoutService;
import com.jhg.hgpage.oms.service.OrderCancellationService;
import com.jhg.hgpage.oms.service.PaymentApprovalProcessor;
import com.jhg.hgpage.oms.service.PaymentFacade;
import com.jhg.hgpage.oms.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceCancelTest {

    @Mock CheckoutService checkoutService;
    @Mock PaymentService paymentService;
    @Mock PaymentApprovalProcessor approvalProcessor;
    @Mock OrderCancellationService cancellationService;
    @InjectMocks PaymentFacade paymentFacade;

    @Test
    void 취소는_동기_WMS경로가_아닌_복구가능한_취소서비스에_위임한다() {
        when(cancellationService.request(10L, 1L))
                .thenReturn(new OrderCancellationService.CancellationResult(
                        OrderCancellationService.CancellationOutcome.REFUND_PENDING));

        assertThat(paymentFacade.cancelOrder(10L, 1L))
                .isEqualTo(OrderCancellationService.CancellationOutcome.REFUND_PENDING);

        verify(cancellationService).request(10L, 1L);
    }
}
