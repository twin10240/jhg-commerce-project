package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.contract.PaymentGateway.ApprovalCommand;
import com.jhg.hgpage.contract.PaymentGateway.ApprovalResult;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final RetrySchedule retrySchedule;

    @Transactional
    public Optional<ApprovalCommand> claimApproval(Long attemptId) {
        PaymentAttempt attempt = paymentAttemptRepository.findByIdForUpdate(attemptId).orElse(null);
        if (attempt == null || attempt.getStatus() != PaymentAttemptStatus.PENDING
                || attempt.getNextAttemptAt().isAfter(LocalDateTime.now())) {
            return Optional.empty();
        }
        LockedPayment locked = lockPayment(attempt);
        if (locked.payment.getStatus() != PaymentStatus.PENDING
                || (locked.order.getStatus() != OrderStatus.PAYMENT_PENDING
                && locked.order.getStatus() != OrderStatus.CANCEL_REQUESTED)) {
            return Optional.empty();
        }
        attempt.claim(LocalDateTime.now());
        return Optional.of(new ApprovalCommand(locked.order.getId(), locked.payment.getOrderAmount(),
                attempt.getRequestKey()));
    }

    @Transactional
    public void applyApprovalResult(Long attemptId, ApprovalResult result) {
        PaymentAttempt attempt = paymentAttemptRepository.findByIdForUpdate(attemptId).orElse(null);
        if (attempt == null || attempt.getStatus() != PaymentAttemptStatus.PROCESSING) {
            return;
        }
        LockedPayment locked = lockPayment(attempt);
        LocalDateTime now = LocalDateTime.now();

        switch (result.outcome()) {
            case SUCCESS -> approve(attempt, locked.payment, locked.order, result, now);
            case DECLINED -> decline(attempt, locked.payment, locked.order, result, now);
            case RETRYABLE_FAILURE, UNKNOWN -> retryOrReview(attempt, locked.payment, locked.order,
                    failureCode(result), failureReason(result), now);
            case PERMANENT_FAILURE -> review(attempt, locked.payment, locked.order,
                    failureCode(result), failureReason(result), now);
        }
    }

    @Transactional
    public Long retryPayment(Long orderId, Long memberId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order", orderId));
        if (!order.getMember().getId().equals(memberId)) {
            throw new EntityNotFoundException("Order", orderId);
        }
        Payment payment = paymentRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Payment", orderId));
        payment.retry();
        order.markPaymentPending();
        return paymentAttemptRepository.save(PaymentAttempt.create(payment, UUID.randomUUID())).getId();
    }

    @Transactional
    public void recoverStaleApprovals(LocalDateTime staleBefore, LocalDateTime now) {
        for (PaymentAttempt attempt : paymentAttemptRepository
                .findTop50ByStatusAndUpdatedAtLessThanEqualOrderById(PaymentAttemptStatus.PROCESSING, staleBefore)) {
            LockedPayment locked = lockPayment(attempt);
            if (attempt.getAttemptCount() >= 5) {
                review(attempt, locked.payment, locked.order, "STALE_PROCESSING", "Processing lease expired", now);
            } else {
                attempt.retryAt(now, "STALE_PROCESSING", "Processing lease expired");
            }
        }
    }

    @Transactional(readOnly = true)
    public List<Long> findDueApprovalAttemptIds(LocalDateTime now) {
        return paymentAttemptRepository.findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderById(
                        List.of(PaymentAttemptStatus.PENDING), now).stream()
                .map(PaymentAttempt::getId)
                .toList();
    }

    private LockedPayment lockPayment(PaymentAttempt attempt) {
        Long orderId = attempt.getPayment().getOrder().getId();
        Payment payment = paymentRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Payment", attempt.getPayment().getId()));
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order", orderId));
        return new LockedPayment(payment, order);
    }

    private void approve(PaymentAttempt attempt, Payment payment, Order order,
                         ApprovalResult result, LocalDateTime now) {
        if (result.transactionId() == null) {
            retryOrReview(attempt, payment, order, "INVALID_GATEWAY_RESULT", "Missing transaction id", now);
            return;
        }
        attempt.succeed(result.transactionId(), now);
        payment.markPaid(now);
        if (order.getStatus() == OrderStatus.CANCEL_REQUESTED) {
            order.resolveCancellationRelease(false);
        } else {
            order.markAllocationPending();
        }
    }

    private void decline(PaymentAttempt attempt, Payment payment, Order order,
                         ApprovalResult result, LocalDateTime now) {
        if (order.getStatus() == OrderStatus.CANCEL_REQUESTED) {
            attempt.cancel(now);
            payment.cancelUnpaid();
            order.resolveCancellationRelease(false);
            order.finishCancellation();
            return;
        }
        attempt.fail(failureCode(result), failureReason(result), now);
        payment.markPaymentFailed();
        order.markPaymentFailed();
    }

    private void retryOrReview(PaymentAttempt attempt, Payment payment, Order order,
                               String code, String reason, LocalDateTime now) {
        Optional<LocalDateTime> nextAttemptAt = retrySchedule.nextAttemptAt(attempt.getAttemptCount(), now);
        if (nextAttemptAt.isPresent()) {
            attempt.retryAt(nextAttemptAt.get(), code, reason);
        } else {
            review(attempt, payment, order, code, reason, now);
        }
    }

    private void review(PaymentAttempt attempt, Payment payment, Order order,
                        String code, String reason, LocalDateTime now) {
        attempt.manualReview(code, reason, now);
        if (payment.getStatus() == PaymentStatus.PENDING) {
            payment.markPaymentReview();
        }
        if (order.getStatus() == OrderStatus.PAYMENT_PENDING) {
            order.markPaymentReview();
        }
    }

    private String failureCode(ApprovalResult result) {
        return result.failureCode() == null ? result.outcome().name() : result.failureCode();
    }

    private String failureReason(ApprovalResult result) {
        return result.failureReason() == null ? result.outcome().name() : result.failureReason();
    }

    private record LockedPayment(Payment payment, Order order) {
    }
}
