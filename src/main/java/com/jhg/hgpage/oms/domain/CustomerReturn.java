package com.jhg.hgpage.oms.domain;

import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import com.jhg.hgpage.oms.domain.enums.ReturnDisposition;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static jakarta.persistence.FetchType.LAZY;

@Entity
@Getter
@Table(name = "customer_return", uniqueConstraints = {
        @UniqueConstraint(name = "uq_customer_return_request_key", columnNames = "request_key"),
        @UniqueConstraint(name = "uq_customer_return_rma_id", columnNames = "rma_id")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerReturn {

    @Id @GeneratedValue
    @Column(name = "customer_return_id")
    private Long id;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "request_key", nullable = false, unique = true)
    private UUID requestKey;

    @Column(name = "rma_id", unique = true)
    private Long rmaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CustomerReturnStatus status;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "failure_reason", length = 100)
    private String failureReason;

    @Column(name = "reviewed_by", length = 255)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @OneToMany(mappedBy = "customerReturn", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CustomerReturnItem> items = new ArrayList<>();

    public static CustomerReturn create(Order order, UUID requestKey, String reason, List<RequestItem> requestItems) {
        if (order == null || order.getDelivery().getStatus() != DeliveryStatus.DELIVERED) {
            throw new IllegalArgumentException("배송 완료 주문만 반품할 수 있습니다.");
        }
        if (requestItems == null || requestItems.isEmpty()) {
            throw new IllegalArgumentException("반품 품목은 필수입니다.");
        }
        CustomerReturn customerReturn = new CustomerReturn();
        customerReturn.order = order;
        customerReturn.requestKey = Objects.requireNonNull(requestKey);
        customerReturn.reason = Objects.requireNonNull(reason);
        customerReturn.status = CustomerReturnStatus.PENDING_APPROVAL;
        customerReturn.requestedAt = LocalDateTime.now();
        customerReturn.updatedAt = customerReturn.requestedAt;
        for (RequestItem requestItem : requestItems) {
            customerReturn.items.add(CustomerReturnItem.create(customerReturn,
                    requestItem.orderItem(), requestItem.quantity()));
        }
        return customerReturn;
    }

    public void approve(String reviewer) {
        requirePendingApproval();
        reviewedBy = requireText(reviewer, "승인자는 필수입니다.", 255);
        reviewedAt = LocalDateTime.now();
        changeStatus(CustomerReturnStatus.PENDING_SUBMISSION);
    }

    public void reject(String reviewer, String reason) {
        requirePendingApproval();
        reviewedBy = requireText(reviewer, "승인자는 필수입니다.", 255);
        rejectionReason = requireText(reason, "반려 사유는 1자 이상 500자 이하여야 합니다.", 500);
        reviewedAt = LocalDateTime.now();
        changeStatus(CustomerReturnStatus.REJECTED);
    }

    public void markRequested(Long rmaId) {
        bindRmaId(rmaId);
        if (status == CustomerReturnStatus.PENDING_SUBMISSION) {
            changeStatus(CustomerReturnStatus.REQUESTED);
            return;
        }
        if (status == CustomerReturnStatus.SUBMISSION_FAILED) {
            throw new IllegalStateException("접수 실패 반품은 요청 상태로 변경할 수 없습니다.");
        }
    }

    public void markReceived() {
        if (status == CustomerReturnStatus.PENDING_SUBMISSION || status == CustomerReturnStatus.REQUESTED) {
            changeStatus(CustomerReturnStatus.RECEIVED);
            return;
        }
        if (status != CustomerReturnStatus.RECEIVED) {
            throw new IllegalStateException("반품 수령 상태로 변경할 수 없습니다.");
        }
    }

    public void complete(List<ResultItem> results) {
        if (status != CustomerReturnStatus.PENDING_SUBMISSION && status != CustomerReturnStatus.REQUESTED
                && status != CustomerReturnStatus.RECEIVED) {
            throw new IllegalStateException("반품 완료 상태로 변경할 수 없습니다.");
        }
        Map<Long, ResultItem> resultsByOrderItemId = resultsByOrderItemId(results);
        if (resultsByOrderItemId.size() != items.size()) {
            throw new IllegalArgumentException("모든 반품 품목의 결과가 필요합니다.");
        }
        for (CustomerReturnItem item : items) {
            ResultItem result = resultsByOrderItemId.get(item.getOrderItem().getId());
            if (result == null) {
                throw new IllegalArgumentException("반품 품목 결과가 일치하지 않습니다.");
            }
            item.validateResult(result.acceptedQuantity(), result.disposition());
        }
        for (CustomerReturnItem item : items) {
            ResultItem result = resultsByOrderItemId.get(item.getOrderItem().getId());
            item.applyResult(result.acceptedQuantity(), result.disposition());
        }
        changeStatus(CustomerReturnStatus.COMPLETED);
        completedAt = updatedAt;
    }

    public void cancel() {
        if (status != CustomerReturnStatus.PENDING_SUBMISSION && status != CustomerReturnStatus.REQUESTED) {
            throw new IllegalStateException("반품 취소 상태로 변경할 수 없습니다.");
        }
        items.forEach(CustomerReturnItem::cancel);
        changeStatus(CustomerReturnStatus.CANCELLED);
        completedAt = updatedAt;
    }

    public void failSubmission(String failureReason) {
        if (status != CustomerReturnStatus.PENDING_SUBMISSION) {
            throw new IllegalStateException("접수 실패 상태로 변경할 수 없습니다.");
        }
        this.failureReason = Objects.requireNonNull(failureReason);
        changeStatus(CustomerReturnStatus.SUBMISSION_FAILED);
    }

    private void bindRmaId(Long rmaId) {
        Objects.requireNonNull(rmaId);
        if (this.rmaId != null && !this.rmaId.equals(rmaId)) {
            throw new IllegalArgumentException("RMA 식별자가 일치하지 않습니다.");
        }
        this.rmaId = rmaId;
    }

    private void requirePendingApproval() {
        if (status != CustomerReturnStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("OMS 승인 대기 상태의 반품만 처리할 수 있습니다.");
        }
    }

    private String requireText(String value, String message, int maxLength) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private Map<Long, ResultItem> resultsByOrderItemId(List<ResultItem> results) {
        if (results == null) {
            throw new IllegalArgumentException("반품 결과는 필수입니다.");
        }
        Map<Long, ResultItem> resultsByOrderItemId = new HashMap<>();
        for (ResultItem result : results) {
            if (result == null || result.orderItemId() == null
                    || resultsByOrderItemId.put(result.orderItemId(), result) != null) {
                throw new IllegalArgumentException("반품 결과 품목이 올바르지 않습니다.");
            }
        }
        return resultsByOrderItemId;
    }

    private void changeStatus(CustomerReturnStatus status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }

    public List<CustomerReturnItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public record RequestItem(OrderItem orderItem, int quantity) {}

    public record ResultItem(Long orderItemId, int acceptedQuantity, ReturnDisposition disposition) {}
}
