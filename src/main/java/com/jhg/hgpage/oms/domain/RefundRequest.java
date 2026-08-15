package com.jhg.hgpage.oms.domain;

import com.jhg.hgpage.oms.domain.enums.RefundSourceType;
import com.jhg.hgpage.oms.domain.enums.RefundStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import static jakarta.persistence.FetchType.LAZY;

@Entity
@Getter
@Table(name = "refund_request", uniqueConstraints = {
        @UniqueConstraint(name = "uq_refund_request_key", columnNames = "request_key"),
        @UniqueConstraint(name = "uq_refund_source", columnNames = {"source_type", "source_id"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundRequest {

    @Id @GeneratedValue
    @Column(name = "refund_request_id")
    private Long id;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "request_key", nullable = false, unique = true)
    private UUID requestKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private RefundSourceType sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RefundStatus status;

    @Column(nullable = false)
    private int attemptCount;

    private LocalDateTime nextAttemptAt;

    private String lastFailureCode;
    private String lastFailureReason;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    @Version
    private Long version;

    public static RefundRequest create(Payment payment, UUID requestKey, RefundSourceType sourceType, Long sourceId, int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("환불 금액은 0보다 커야 합니다.");
        }
        RefundRequest request = new RefundRequest();
        request.payment = Objects.requireNonNull(payment);
        request.requestKey = Objects.requireNonNull(requestKey);
        request.sourceType = Objects.requireNonNull(sourceType);
        request.sourceId = Objects.requireNonNull(sourceId);
        request.amount = amount;
        request.status = RefundStatus.PENDING;
        request.createdAt = LocalDateTime.now();
        request.updatedAt = request.createdAt;
        request.nextAttemptAt = request.createdAt;
        return request;
    }

    public void claim(LocalDateTime now) {
        if ((status != RefundStatus.PENDING && status != RefundStatus.RETRYING) || nextAttemptAt.isAfter(now)) {
            throw new IllegalStateException("환불 요청을 선점할 수 없습니다.");
        }
        status = RefundStatus.PROCESSING;
        attemptCount++;
        nextAttemptAt = null;
        touch(now);
    }

    public void retryAt(LocalDateTime nextAttemptAt, String failureCode, String failureReason, LocalDateTime now) {
        requireProcessing();
        recordFailure(failureCode, failureReason);
        status = RefundStatus.RETRYING;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt);
        touch(Objects.requireNonNull(now));
    }

    public void manualReview(String failureCode, String failureReason, LocalDateTime now) {
        requireProcessing();
        recordFailure(failureCode, failureReason);
        status = RefundStatus.MANUAL_REVIEW;
        touch(Objects.requireNonNull(now));
    }

    public void succeed(LocalDateTime completedAt) {
        requireProcessing();
        status = RefundStatus.SUCCEEDED;
        this.completedAt = Objects.requireNonNull(completedAt);
        touch(completedAt);
    }

    private void requireProcessing() {
        if (status != RefundStatus.PROCESSING) {
            throw new IllegalStateException("처리 중인 환불 요청이 아닙니다.");
        }
    }

    private void recordFailure(String failureCode, String failureReason) {
        lastFailureCode = Objects.requireNonNull(failureCode);
        lastFailureReason = Objects.requireNonNull(failureReason);
    }

    private void touch(LocalDateTime now) {
        updatedAt = now;
    }
}
