package com.jhg.hgpage.oms.dto;

import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.Payment;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
@AllArgsConstructor
public class OrderDto {
    private Long id;

    private OrderStatus status;

    private DeliveryStatus deliveryStatus;

    private int totalAmount;

    private LocalDateTime createdAt;

    private String orderStatusLabel;

    private String paymentStatusLabel;

    private int paidAmount;

    private int pendingRefundAmount;

    private int refundedAmount;

    private boolean paymentRetryable;

    private String refundStatusLabel;

    public OrderDto(Long id, OrderStatus status, DeliveryStatus deliveryStatus,
                    int totalAmount, LocalDateTime createdAt) {
        this(id, status, deliveryStatus, totalAmount, createdAt,
                PaymentViewDto.legacy(status, deliveryStatus));
    }

    private OrderDto(Long id, OrderStatus status, DeliveryStatus deliveryStatus,
                     int totalAmount, LocalDateTime createdAt, PaymentViewDto payment) {
        this.id = id;
        this.status = status;
        this.deliveryStatus = deliveryStatus;
        this.totalAmount = totalAmount;
        this.createdAt = createdAt;
        this.orderStatusLabel = payment.orderStatusLabel();
        this.paymentStatusLabel = payment.paymentStatusLabel();
        this.paidAmount = payment.paidAmount();
        this.pendingRefundAmount = payment.pendingRefundAmount();
        this.refundedAmount = payment.refundedAmount();
        this.paymentRetryable = payment.paymentRetryable();
        this.refundStatusLabel = payment.refundStatusLabel();
    }

    public static OrderDto from(Order order, Payment payment) {
        return new OrderDto(order.getId(), order.getStatus(), order.getDelivery().getStatus(),
                order.getTotalPrice(), order.getOrderDate(), PaymentViewDto.from(order, payment));
    }

    public boolean isCompleted() {
        return status == OrderStatus.CANCEL || deliveryStatus == DeliveryStatus.DELIVERED;
    }

}
