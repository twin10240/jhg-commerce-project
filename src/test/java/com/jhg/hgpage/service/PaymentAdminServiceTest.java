package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.service.AllocationProcessor;
import com.jhg.hgpage.oms.service.CancellationProcessor;
import com.jhg.hgpage.oms.service.OrderAllocationService;
import com.jhg.hgpage.oms.service.OrderCancellationService;
import com.jhg.hgpage.oms.service.PaymentAdminService;
import com.jhg.hgpage.oms.service.PaymentApprovalProcessor;
import com.jhg.hgpage.oms.service.PaymentService;
import com.jhg.hgpage.oms.service.RefundProcessor;
import com.jhg.hgpage.oms.service.RefundService;
import com.jhg.hgpage.oms.domain.enums.PaymentStatus;
import com.jhg.hgpage.oms.domain.enums.RefundStatus;
import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentAdminServiceTest {

    @Mock RefundService refundService;
    @Mock RefundProcessor refundProcessor;
    @Mock PaymentService paymentService;
    @Mock PaymentApprovalProcessor paymentApprovalProcessor;
    @Mock OrderAllocationService orderAllocationService;
    @Mock AllocationProcessor allocationProcessor;
    @Mock OrderCancellationService cancellationService;
    @Mock CancellationProcessor cancellationProcessor;
    @Mock OrderRepositoryQuery orderRepositoryQuery;

    PaymentAdminService service;

    @BeforeEach
    void setUp() {
        service = new PaymentAdminService(orderRepositoryQuery, refundService, refundProcessor,
                paymentService, paymentApprovalProcessor,
                orderAllocationService, allocationProcessor,
                cancellationService, cancellationProcessor);
    }

    @Test
    void 화면필터와_네가지_검토건수를_서로_분리해_조회한다() {
        when(orderRepositoryQuery.findPaymentsForAdmin(PaymentStatus.PAYMENT_REVIEW)).thenReturn(java.util.List.of());
        when(orderRepositoryQuery.findRefundsForAdmin(RefundStatus.MANUAL_REVIEW)).thenReturn(java.util.List.of());
        when(orderRepositoryQuery.countRefundReviews()).thenReturn(1L);
        when(orderRepositoryQuery.countAllocationReviews()).thenReturn(2L);
        when(paymentService.findCancellationReviewAttemptIds()).thenReturn(java.util.List.of(7L, 8L, 9L));
        when(orderAllocationService.findCancellationAllocationReviewOrderIds())
                .thenReturn(java.util.List.of(10L, 11L, 12L, 13L));

        PaymentAdminService.PageView page = service.findPage(
                PaymentStatus.PAYMENT_REVIEW, RefundStatus.MANUAL_REVIEW);

        assertThat(page.counts()).isEqualTo(new PaymentAdminService.ReviewCounts(1, 2, 3, 4));
        verify(orderRepositoryQuery).findPaymentsForAdmin(PaymentStatus.PAYMENT_REVIEW);
        verify(orderRepositoryQuery).findRefundsForAdmin(RefundStatus.MANUAL_REVIEW);
    }

    @Test
    void 수동검토_환불만_기존요청을_재큐한뒤_처리한다() {
        when(refundService.requeueReview(7L)).thenReturn(true, false);

        service.retryRefund(7L);
        service.retryRefund(7L);

        verify(refundProcessor).process(7L);
    }

    @Test
    void 취소결제검토만_기존시도를_재큐한뒤_처리한다() {
        when(paymentService.requeueCancellationReview(8L)).thenReturn(true, false);

        service.retryCancellationPayment(8L);
        service.retryCancellationPayment(8L);

        verify(paymentApprovalProcessor).process(8L);
    }

    @Test
    void 일반_할당검토는_같은주문을_재큐하고_할당처리한다() {
        when(orderAllocationService.requeueAllocationReview(9L)).thenReturn(true);

        service.retryAllocation(9L);

        verify(allocationProcessor).process(9L);
        verify(cancellationProcessor, never()).process(9L);
    }

    @Test
    void 미확정_취소할당은_같은주문을_재큐하고_할당처리한다() {
        when(orderAllocationService.requeueCancellationAllocation(10L)).thenReturn(true);

        service.retryAllocation(10L);

        verify(allocationProcessor).process(10L);
        verify(cancellationProcessor, never()).process(10L);
    }

    @Test
    void 해제실패_취소검토는_같은주문을_재큐하고_취소처리한다() {
        when(cancellationService.requeueCancellationReview(11L)).thenReturn(true);

        service.retryAllocation(11L);

        verify(cancellationProcessor).process(11L);
        verify(allocationProcessor, never()).process(11L);
    }

    @Test
    void 잘못되거나_반복된_재시도는_외부처리기를_호출하지않는다() {
        service.retryRefund(7L);
        service.retryCancellationPayment(8L);
        service.retryAllocation(9L);

        verify(refundProcessor, never()).process(7L);
        verify(paymentApprovalProcessor, never()).process(8L);
        verify(allocationProcessor, never()).process(9L);
        verify(cancellationProcessor, never()).process(9L);
    }
}
