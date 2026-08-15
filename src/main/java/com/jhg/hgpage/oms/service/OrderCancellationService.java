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

    @Transactional
    public CancellationResult request(Long orderId, Long memberId) {
        PaymentAttempt activeAttempt = paymentAttemptRepository
                .findFirstByPaymentOrderIdAndStatusInOrderByIdDesc(orderId, ACTIVE_APPROVAL_STATUSES)
                .orElse(null);
        Payment payment = paymentRepository.findByOrderIdForUpdate(orderId).orElse(null);
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order", orderId));
        if (!order.getMember().getId().equals(memberId)) {
            throw new EntityNotFoundException("Order", orderId);
        }

        boolean paid = isPaid(payment);
        if (order.getStatus() == OrderStatus.CANCEL || order.getStatus() == OrderStatus.CANCEL_REQUESTED) {
            return new CancellationResult(paid);
        }

        switch (order.getStatus()) {
            case PAYMENT_PENDING -> cancelPending(order, payment, activeAttempt);
            case PAYMENT_FAILED -> {
                if (payment != null) {
                    payment.cancelUnpaid();
                }
                finishWithoutRelease(order);
            }
            case PAYMENT_REVIEW -> order.requestCancellation(null, LocalDateTime.now());
            case ALLOCATION_PENDING, ALLOCATION_REVIEW, BACKORDERED -> {
                finishWithoutRelease(order);
                if (paid) {
                    refundService.requestOrderCancellationRefund(orderId);
                }
            }
            case ALLOCATION_PROCESSING -> order.requestCancellation(null, LocalDateTime.now());
            case ORDER -> order.requestCancellation(true, LocalDateTime.now());
            default -> throw new IllegalStateException("주문 취소를 요청할 수 없습니다.");
        }
        return new CancellationResult(paid);
    }

    @Transactional
    public Optional<CancellationClaim> claim(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.CANCEL_REQUESTED
                || order.getCancellationReleaseRequired() == null
                || order.getCancellationProcessingAt() != null) {
            return Optional.empty();
        }
        order.claimCancellation(LocalDateTime.now());
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
        return true;
    }

    @Transactional
    public void retry(Long orderId, int attemptNumber) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (ownsCancellationLease(order, attemptNumber)) {
            order.setCancellationProcessingAt(null);
        }
    }

    @Transactional
    public void recoverStaleCancellations(LocalDateTime staleBefore) {
        for (Long orderId : orderRepository.findStaleCancellationOrderIds(staleBefore)) {
            Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
            if (order != null && order.getStatus() == OrderStatus.CANCEL_REQUESTED
                    && order.getCancellationProcessingAt() != null
                    && !order.getCancellationProcessingAt().isAfter(staleBefore)) {
                order.setCancellationProcessingAt(null);
            }
        }
    }

    public List<Long> findDueCancellationOrderIds() {
        return orderRepository.findDueCancellationOrderIds();
    }

    private void cancelPending(Order order, Payment payment, PaymentAttempt activeAttempt) {
        if (payment == null || payment.getStatus() != PaymentStatus.PENDING
                || activeAttempt == null || activeAttempt.getStatus() == PaymentAttemptStatus.PROCESSING) {
            order.requestCancellation(null, LocalDateTime.now());
            return;
        }
        activeAttempt.cancel(LocalDateTime.now());
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

    private boolean isPaid(Payment payment) {
        return payment != null && payment.getPaidAmount() > 0;
    }

    public record CancellationResult(boolean paid) {
    }

    public record CancellationClaim(int attemptNumber, boolean releaseRequired, Map<Long, Integer> quantities) {
    }
}
