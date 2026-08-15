package com.jhg.hgpage.oms.domain;

import com.jhg.hgpage.oms.domain.enums.PaymentAttemptStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import static jakarta.persistence.FetchType.LAZY;

@Entity
@Getter
@Table(name = "payment_attempt")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAttempt {

    @Id @GeneratedValue
    @Column(name = "payment_attempt_id")
    private Long id;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "request_key", nullable = false, unique = true)
    private UUID requestKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentAttemptStatus status;

    private String gatewayTransactionId;

    @Column(nullable = false)
    private int attemptCount;

    private LocalDateTime nextAttemptAt;
    private String failureCode;
    private String failureReason;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    public static PaymentAttempt create(Payment payment, UUID requestKey) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.payment = Objects.requireNonNull(payment);
        attempt.requestKey = Objects.requireNonNull(requestKey);
        attempt.status = PaymentAttemptStatus.PENDING;
        attempt.createdAt = LocalDateTime.now();
        attempt.updatedAt = attempt.createdAt;
        attempt.nextAttemptAt = attempt.createdAt;
        return attempt;
    }

    public void claim(LocalDateTime now) {
        if (status != PaymentAttemptStatus.PENDING || nextAttemptAt.isAfter(now)) {
            throw new IllegalStateException("결제 시도를 선점할 수 없습니다.");
        }
        status = PaymentAttemptStatus.PROCESSING;
        attemptCount++;
        nextAttemptAt = null;
        touch(now);
    }

    public void succeed(String gatewayTransactionId, LocalDateTime completedAt) {
        requireProcessing();
        this.gatewayTransactionId = Objects.requireNonNull(gatewayTransactionId);
        status = PaymentAttemptStatus.SUCCEEDED;
        this.completedAt = Objects.requireNonNull(completedAt);
        touch(completedAt);
    }

    public void fail(String failureCode, String failureReason, LocalDateTime completedAt) {
        requireProcessing();
        recordFailure(failureCode, failureReason);
        status = PaymentAttemptStatus.FAILED;
        this.completedAt = Objects.requireNonNull(completedAt);
        touch(completedAt);
    }

    public void retryAt(LocalDateTime nextAttemptAt, String failureCode, String failureReason) {
        requireProcessing();
        recordFailure(failureCode, failureReason);
        status = PaymentAttemptStatus.PENDING;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt);
        touch(LocalDateTime.now());
    }

    public void manualReview(String failureCode, String failureReason, LocalDateTime now) {
        requireProcessing();
        recordFailure(failureCode, failureReason);
        status = PaymentAttemptStatus.MANUAL_REVIEW;
        touch(Objects.requireNonNull(now));
    }

    public void cancel(LocalDateTime now) {
        if (status != PaymentAttemptStatus.PENDING && status != PaymentAttemptStatus.PROCESSING
                && status != PaymentAttemptStatus.MANUAL_REVIEW) {
            throw new IllegalStateException("결제 시도를 취소할 수 없습니다.");
        }
        status = PaymentAttemptStatus.CANCELLED;
        nextAttemptAt = null;
        completedAt = Objects.requireNonNull(now);
        touch(now);
    }

    private void requireProcessing() {
        if (status != PaymentAttemptStatus.PROCESSING) {
            throw new IllegalStateException("처리 중인 결제 시도가 아닙니다.");
        }
    }

    private void recordFailure(String failureCode, String failureReason) {
        this.failureCode = Objects.requireNonNull(failureCode);
        this.failureReason = Objects.requireNonNull(failureReason);
    }

    private void touch(LocalDateTime now) {
        updatedAt = now;
    }
}
