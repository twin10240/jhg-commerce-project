package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.PaymentAttempt;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.domain.enums.PaymentAttemptStatus;
import com.jhg.hgpage.oms.domain.enums.PaymentStatus;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.repository.PaymentAttemptRepository;
import com.jhg.hgpage.oms.repository.PaymentRepository;
import com.jhg.hgpage.realtime.outbox.NotificationEventType;
import com.jhg.hgpage.realtime.outbox.NotificationEventWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderCancellationService {

    private static final List<PaymentAttemptStatus> ACTIVE_APPROVAL_STATUSES =
            List.of(PaymentAttemptStatus.PENDING, PaymentAttemptStatus.PROCESSING);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final RefundService refundService;
    private final RetrySchedule retrySchedule;
    private final NotificationEventWriter eventWriter;

    @Transactional
    public CancellationResult request(Long orderId, Long memberId) {
        Payment payment = paymentRepository.findByOrderIdForUpdate(orderId).orElse(null);
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order", orderId));
        if (!order.getMember().getId().equals(memberId)) {
            throw new EntityNotFoundException("Order", orderId);
        }

        if (order.getStatus() == OrderStatus.CANCEL || order.getStatus() == OrderStatus.CANCEL_REQUESTED) {
            return result(order, payment);
        }

        PaymentAttempt activeAttempt = paymentAttemptRepository
                .findFirstByPaymentOrderIdAndStatusInOrderByIdDesc(orderId, ACTIVE_APPROVAL_STATUSES)
                .orElse(null);
        boolean paid = isPaid(payment);

        switch (order.getStatus()) {
            case PAYMENT_PENDING -> cancelPending(order, payment, activeAttempt);
            case PAYMENT_FAILED -> {
                if (payment != null) {
                    payment.cancelUnpaid();
                }
                finishWithoutRelease(order);
            }
            case PAYMENT_REVIEW -> order.requestCancellation(null, LocalDateTime.now());
            case ALLOCATION_PENDING -> {
                if (order.getAllocationAttemptCount() > 0) {
                    order.requestCancellation(null, LocalDateTime.now());
                } else {
                    finishWithoutRelease(order);
                    if (paid) {
                        refundService.requestOrderCancellationRefund(orderId);
                    }
                }
            }
            case ALLOCATION_REVIEW -> {
                if (isDefinitiveAllocationRejection(order)) {
                    finishWithoutRelease(order);
                    if (paid) {
                        refundService.requestOrderCancellationRefund(orderId);
                    }
                } else {
                    order.requestCancellation(null, LocalDateTime.now());
                }
            }
            case BACKORDERED -> {
                finishWithoutRelease(order);
                if (paid) {
                    refundService.requestOrderCancellationRefund(orderId);
                }
            }
            case ALLOCATION_PROCESSING -> order.requestCancellation(null, LocalDateTime.now());
            case ORDER -> order.requestCancellation(true, LocalDateTime.now());
            default -> throw new IllegalStateException("주문 취소를 요청할 수 없습니다.");
        }
        if (order.getStatus() == OrderStatus.CANCEL) {
            appendCancelled(order);
        }
        return result(order, payment);
    }

    @Transactional
    public Optional<CancellationClaim> claim(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (order == null || order.getStatus() != OrderStatus.CANCEL_REQUESTED
                || order.getCancellationReleaseRequired() == null
                || order.getCancellationProcessingAt() != null
                || order.getCancellationNextAttemptAt() == null
                || order.getCancellationNextAttemptAt().isAfter(now)) {
            return Optional.empty();
        }
        order.claimCancellation(now);
        return Optional.of(new CancellationClaim(order.getCancellationAttemptCount(),
                order.getCancellationReleaseRequired(), Map.copyOf(order.quantitiesByProductId())));
    }

    @Transactional
    public boolean complete(Long orderId, int attemptNumber) {
        Payment payment = paymentRepository.findByOrderIdForUpdate(orderId).orElse(null);
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (!ownsCancellationLease(order, attemptNumber)) {
            return false;
        }
        order.finishCancellation();
        if (isPaid(payment)) {
            refundService.requestOrderCancellationRefund(orderId);
        }
        appendCancelled(order);
        return true;
    }

    @Transactional
    public void retryOrReview(Long orderId, int attemptNumber, String failureCode) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (ownsCancellationLease(order, attemptNumber)) {
            retryOrReview(order, failureCode, LocalDateTime.now());
        }
    }

    @Transactional
    public void manualReview(Long orderId, int attemptNumber, String failureCode) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (ownsCancellationLease(order, attemptNumber)) {
            order.reviewCancellation(failureCode);
        }
    }

    @Transactional
    public void recoverStaleCancellations(LocalDateTime staleBefore, LocalDateTime now) {
        for (Long orderId : orderRepository.findStaleCancellationOrderIds(staleBefore)) {
            Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
            if (order != null && order.getStatus() == OrderStatus.CANCEL_REQUESTED
                    && order.getCancellationProcessingAt() != null
                    && !order.getCancellationProcessingAt().isAfter(staleBefore)) {
                retryOrReview(order, "STALE_PROCESSING", now);
            }
        }
    }

    public List<Long> findDueCancellationOrderIds() {
        return orderRepository.findDueCancellationOrderIds(LocalDateTime.now());
    }

    @Transactional
    public boolean requeueCancellationReview(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.CANCEL_REQUESTED
                || !Boolean.TRUE.equals(order.getCancellationReleaseRequired())
                || order.getCancellationAttemptCount() == 0
                || order.getCancellationProcessingAt() != null
                || order.getCancellationNextAttemptAt() != null) {
            return false;
        }
        order.requeueCancellationReview(LocalDateTime.now());
        return true;
    }

    private void cancelPending(Order order, Payment payment, PaymentAttempt activeAttempt) {
        if (payment == null || payment.getStatus() != PaymentStatus.PENDING
                || activeAttempt != null && (activeAttempt.getStatus() == PaymentAttemptStatus.PROCESSING
                || activeAttempt.getAttemptCount() > 0)) {
            order.requestCancellation(null, LocalDateTime.now());
            return;
        }
        if (activeAttempt != null) {
            activeAttempt.cancel(LocalDateTime.now());
        }
        payment.cancelUnpaid();
        finishWithoutRelease(order);
    }

    private void finishWithoutRelease(Order order) {
        order.requestCancellation(false, LocalDateTime.now());
        order.finishCancellation();
    }

    private boolean ownsCancellationLease(Order order, int attemptNumber) {
        return order != null && order.getStatus() == OrderStatus.CANCEL_REQUESTED
                && order.getCancellationProcessingAt() != null
                && order.getCancellationAttemptCount() == attemptNumber;
    }

    private void retryOrReview(Order order, String failureCode, LocalDateTime now) {
        retrySchedule.nextAttemptAt(order.getCancellationAttemptCount(), now)
                .ifPresentOrElse(
                        next -> order.retryCancellation(next, failureCode),
                        () -> order.reviewCancellation(failureCode));
    }

    private boolean isPaid(Payment payment) {
        return payment != null && payment.getPaidAmount() > 0;
    }

    private boolean isDefinitiveAllocationRejection(Order order) {
        String failureCode = order.getAllocationFailureCode();
        return failureCode != null && failureCode.matches("WMS_4\\d{2}");
    }

    private void appendCancelled(Order order) {
        eventWriter.append(NotificationEventType.ORDER_CANCELLED, order.getMember().getId(),
                "ORDER", order.getId().toString(), Map.of("orderId", order.getId()));
    }

    private CancellationResult result(Order order, Payment payment) {
        if (order.getStatus() != OrderStatus.CANCEL) {
            return new CancellationResult(isPaid(payment)
                    ? CancellationOutcome.REFUND_PENDING : CancellationOutcome.PENDING);
        }
        boolean refundOutstanding = isPaid(payment)
                && payment.getRefundedAmount() < payment.getPaidAmount();
        return new CancellationResult(refundOutstanding
                ? CancellationOutcome.REFUND_PENDING : CancellationOutcome.COMPLETED);
    }

    public enum CancellationOutcome {
        COMPLETED, PENDING, REFUND_PENDING
    }

    public record CancellationResult(CancellationOutcome outcome) {
    }

    public record CancellationClaim(int attemptNumber, boolean releaseRequired, Map<Long, Integer> quantities) {
    }
}
