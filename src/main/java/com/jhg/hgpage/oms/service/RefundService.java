package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.contract.PaymentGateway;
import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.RefundRequest;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.domain.enums.RefundSourceType;
import com.jhg.hgpage.oms.domain.enums.RefundStatus;
import com.jhg.hgpage.oms.repository.PaymentRepository;
import com.jhg.hgpage.oms.repository.RefundRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToIntFunction;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefundService {

    private static final String STALE_PROCESSING = "STALE_PROCESSING";

    private final PaymentRepository paymentRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final RetrySchedule retrySchedule;

    @Transactional
    public Optional<Long> requestOrderCancellationRefund(Long orderId) {
        Payment payment = paymentRepository.findByOrderIdForUpdate(orderId).orElse(null);
        return request(payment, RefundSourceType.ORDER_CANCEL, orderId, Payment::getPaidAmount);
    }

    @Transactional
    public Optional<Long> requestReturnRefund(CustomerReturn customerReturn) {
        if (customerReturn.getStatus() != CustomerReturnStatus.COMPLETED) {
            throw new IllegalStateException("완료된 반품만 환불할 수 있습니다.");
        }
        Payment payment = paymentRepository.findByOrderIdForUpdate(
                customerReturn.getOrder().getId()).orElse(null);
        if (payment == null) {
            return Optional.empty();
        }
        int amount = customerReturn.getItems().stream()
                .mapToInt(item -> Math.multiplyExact(item.getOrderItem().getOrderPrice(), item.getAcceptedQuantity()))
                .reduce(0, Math::addExact);
        return request(payment, RefundSourceType.RETURN,
                customerReturn.getId(), ignored -> amount);
    }

    @Transactional
    public Optional<RefundClaim> claim(Long refundId) {
        RefundRequest request = refundRequestRepository.findByIdForUpdate(refundId).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (request == null
                || request.getStatus() != RefundStatus.PENDING && request.getStatus() != RefundStatus.RETRYING
                || request.getNextAttemptAt() == null || request.getNextAttemptAt().isAfter(now)) {
            return Optional.empty();
        }
        request.claim(now);
        return Optional.of(new RefundClaim(request.getAttemptCount(), new PaymentGateway.RefundCommand(
                request.getPayment().getId(), request.getId(), request.getAmount(), request.getRequestKey())));
    }

    @Transactional
    public void applyResult(Long refundId, int attemptNumber, PaymentGateway.RefundResult result) {
        RefundRequest request = refundRequestRepository.findByIdForUpdate(refundId).orElse(null);
        if (request == null || request.getStatus() != RefundStatus.PROCESSING
                || request.getAttemptCount() != attemptNumber) {
            return;
        }
        Payment payment = paymentRepository.findByOrderIdForUpdate(
                request.getPayment().getOrder().getId()).orElse(null);
        if (payment == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        switch (result.outcome()) {
            case SUCCESS -> {
                if (result.transactionId() == null) {
                    retryOrReview(request, "INVALID_GATEWAY_RESULT", "Missing transaction id", now);
                } else {
                    request.succeed(now);
                    payment.completeRefund(request.getAmount());
                }
            }
            case DECLINED, PERMANENT_FAILURE -> request.manualReview(
                    failureCode(result), failureReason(result), now);
            case RETRYABLE_FAILURE, UNKNOWN -> retryOrReview(
                    request, failureCode(result), failureReason(result), now);
        }
    }

    @Transactional
    public void recoverStaleRefunds(LocalDateTime staleBefore, LocalDateTime now) {
        for (RefundRequest request : refundRequestRepository
                .findTop50ByStatusAndUpdatedAtLessThanEqualOrderById(RefundStatus.PROCESSING, staleBefore)) {
            if (request.getUpdatedAt().isAfter(staleBefore)) {
                continue;
            }
            if (request.getAttemptCount() >= 5) {
                request.manualReview(STALE_PROCESSING, "Processing lease expired", now);
            } else {
                request.retryAt(now, STALE_PROCESSING, "Processing lease expired", now);
            }
        }
    }

    public List<Long> findDueRefundIds(LocalDateTime now) {
        return refundRequestRepository.findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderById(
                        List.of(RefundStatus.PENDING, RefundStatus.RETRYING), now).stream()
                .map(RefundRequest::getId)
                .toList();
    }

    private Optional<Long> request(Payment payment, RefundSourceType sourceType, Long sourceId,
                                   ToIntFunction<Payment> amount) {
        if (payment == null) {
            return Optional.empty();
        }
        Optional<RefundRequest> existing = refundRequestRepository
                .findBySourceTypeAndSourceId(sourceType, sourceId);
        if (existing.isPresent()) {
            return existing.map(RefundRequest::getId);
        }
        int requestedAmount = amount.applyAsInt(payment);
        if (requestedAmount == 0) {
            return Optional.empty();
        }
        payment.reserveRefund(requestedAmount);
        RefundRequest saved = refundRequestRepository.save(RefundRequest.create(
                payment, UUID.randomUUID(), sourceType, sourceId, requestedAmount));
        return Optional.of(saved.getId());
    }

    private void retryOrReview(RefundRequest request, String code, String reason, LocalDateTime now) {
        retrySchedule.nextAttemptAt(request.getAttemptCount(), now)
                .ifPresentOrElse(
                        next -> request.retryAt(next, code, reason, now),
                        () -> request.manualReview(code, reason, now));
    }

    private String failureCode(PaymentGateway.RefundResult result) {
        return result.failureCode() == null ? result.outcome().name() : result.failureCode();
    }

    private String failureReason(PaymentGateway.RefundResult result) {
        return result.failureReason() == null ? result.outcome().name() : result.failureReason();
    }

    public record RefundClaim(int attemptNumber, PaymentGateway.RefundCommand command) {
    }
}
