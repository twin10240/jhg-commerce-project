package com.jhg.hgpage.oms.domain;

import com.jhg.hgpage.oms.domain.enums.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

import static jakarta.persistence.FetchType.LAZY;

@Entity
@Getter
@Table(name = "payment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id @GeneratedValue
    @Column(name = "payment_id")
    private Long id;

    @OneToOne(fetch = LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @Column(nullable = false)
    private int orderAmount;

    @Column(nullable = false)
    private int paidAmount;

    @Column(nullable = false)
    private int pendingRefundAmount;

    @Column(nullable = false)
    private int refundedAmount;

    private LocalDateTime approvedAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    public static Payment create(Order order, int orderAmount) {
        if (orderAmount < 0) {
            throw new IllegalArgumentException("결제 금액은 0 이상이어야 합니다.");
        }
        Payment payment = new Payment();
        payment.order = Objects.requireNonNull(order);
        payment.orderAmount = orderAmount;
        payment.status = PaymentStatus.PENDING;
        payment.updatedAt = LocalDateTime.now();
        return payment;
    }

    public void markPaid(LocalDateTime approvedAt) {
        if (status != PaymentStatus.PENDING && status != PaymentStatus.PAYMENT_REVIEW) {
            throw new IllegalStateException("승인 가능한 결제 상태가 아닙니다.");
        }
        this.status = PaymentStatus.PAID;
        this.paidAmount = orderAmount;
        this.approvedAt = Objects.requireNonNull(approvedAt);
        touch(approvedAt);
    }

    public void markPaymentFailed() {
        changeFromPending(PaymentStatus.PAYMENT_FAILED);
    }

    public void markPaymentReview() {
        changeFromPending(PaymentStatus.PAYMENT_REVIEW);
    }

    public void retry() {
        if (status != PaymentStatus.PAYMENT_FAILED) {
            throw new IllegalStateException("재결제 가능한 상태가 아닙니다.");
        }
        status = PaymentStatus.PENDING;
        touch(LocalDateTime.now());
    }

    public void reserveRefund(int amount) {
        if (amount <= 0 || (long) refundedAmount + pendingRefundAmount + amount > paidAmount) {
            throw new IllegalStateException("환불 가능 금액을 초과했습니다.");
        }
        pendingRefundAmount += amount;
        touch(LocalDateTime.now());
    }

    public void completeRefund(int amount) {
        if (amount <= 0 || amount > pendingRefundAmount) {
            throw new IllegalStateException("처리 중인 환불 금액을 초과했습니다.");
        }
        pendingRefundAmount -= amount;
        refundedAmount += amount;
        status = refundedAmount == paidAmount ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
        touch(LocalDateTime.now());
    }

    public void cancelUnpaid() {
        if (status != PaymentStatus.PENDING && status != PaymentStatus.PAYMENT_FAILED) {
            throw new IllegalStateException("미결제 취소가 가능한 상태가 아닙니다.");
        }
        status = PaymentStatus.CANCELLED;
        touch(LocalDateTime.now());
    }

    private void changeFromPending(PaymentStatus target) {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException("결제 처리 상태를 변경할 수 없습니다.");
        }
        status = target;
        touch(LocalDateTime.now());
    }

    private void touch(LocalDateTime now) {
        updatedAt = now;
    }
}
