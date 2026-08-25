package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.oms.domain.PaymentAttempt;
import com.jhg.hgpage.oms.domain.enums.PaymentStatus;
import com.jhg.hgpage.oms.domain.enums.RefundStatus;
import com.jhg.hgpage.oms.dto.AdminPaymentDto;
import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PaymentAdminService {

    private final OrderRepositoryQuery orderRepositoryQuery;
    private final RefundService refundService;
    private final RefundProcessor refundProcessor;
    private final PaymentService paymentService;
    private final PaymentApprovalProcessor paymentApprovalProcessor;
    private final OrderAllocationService orderAllocationService;
    private final AllocationProcessor allocationProcessor;
    private final OrderCancellationService cancellationService;
    private final CancellationProcessor cancellationProcessor;

    @Transactional(readOnly = true)
    public PageView findPage(boolean refundTab, PaymentStatus paymentStatus, RefundStatus refundStatus) {
        Set<Long> cancellationPaymentIds = new HashSet<>(paymentService.findCancellationReviewAttemptIds());
        List<AdminPaymentDto> paymentRows = List.of();
        List<AdminPaymentDto> refundRows = List.of();
        if (refundTab) {
            refundRows = orderRepositoryQuery.findRefundsForAdmin(refundStatus).stream()
                    .map(AdminPaymentDto::refund)
                    .toList();
        } else {
            var payments = orderRepositoryQuery.findPaymentsForAdmin(paymentStatus);
            Map<Long, PaymentAttempt> latestAttempts = new HashMap<>();
            orderRepositoryQuery.findAttemptsForPaymentIds(payments.stream().map(payment -> payment.getId()).toList())
                    .forEach(attempt -> latestAttempts.putIfAbsent(attempt.getPayment().getId(), attempt));
            paymentRows = payments.stream()
                    .map(payment -> {
                        PaymentAttempt attempt = latestAttempts.get(payment.getId());
                        return AdminPaymentDto.payment(payment, attempt,
                                attempt != null && cancellationPaymentIds.contains(attempt.getId()));
                    })
                    .toList();
        }
        ReviewCounts counts = new ReviewCounts(
                Math.toIntExact(orderRepositoryQuery.countRefundReviews()),
                Math.toIntExact(orderRepositoryQuery.countAllocationReviews()),
                cancellationPaymentIds.size(),
                orderAllocationService.findCancellationAllocationReviewOrderIds().size());
        return new PageView(paymentRows, refundRows, counts);
    }

    public void retryRefund(Long refundId) {
        if (refundService.requeueReview(refundId)) {
            refundProcessor.process(refundId);
        }
    }

    public void retryCancellationPayment(Long attemptId) {
        if (paymentService.requeueCancellationReview(attemptId)) {
            paymentApprovalProcessor.process(attemptId);
        }
    }

    public void retryAllocation(Long orderId) {
        if (orderAllocationService.requeueAllocationReview(orderId)
                || orderAllocationService.requeueCancellationAllocation(orderId)) {
            allocationProcessor.process(orderId);
        } else if (cancellationService.requeueCancellationReview(orderId)) {
            cancellationProcessor.process(orderId);
        }
    }

    public record PageView(List<AdminPaymentDto> payments,
                           List<AdminPaymentDto> refunds,
                           ReviewCounts counts) {
    }

    public record ReviewCounts(int refundReviewCount,
                               int allocationReviewCount,
                               int cancellationPaymentReviewCount,
                               int cancellationAllocationReviewCount) {
    }
}
