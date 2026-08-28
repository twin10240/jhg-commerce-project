package com.jhg.hgpage.oms.dto;

import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
public class AdminOrderDto {

    private final Long id;
    private final String memberName;
    private final OrderStatus status;
    private final DeliveryStatus deliveryStatus;
    private final String carrierName;
    private final String trackingNumber;
    private final Instant shipmentIssuedAt;
    private final int totalPrice;
    private final LocalDateTime orderDate;
    private final List<Item> items;
    private final boolean shippable;
    private final boolean deliverable;
    private final String orderStatusLabel;
    private final String paymentStatusLabel;
    private final boolean allocationRetryable;
    private final boolean cancellationAllocationRetryable;

    public record Item(Long productId, String productName, int quantity, boolean inboundRequired) {}

    private AdminOrderDto(Order order, Map<Long, Integer> availability,
                          Payment payment, boolean cancellationAllocationRetryable) {
        this.id = order.getId();
        this.memberName = order.getMember().getName();
        this.status = order.getStatus();
        this.deliveryStatus = order.getDelivery().getStatus();
        this.carrierName = order.getDelivery().getCarrierName();
        this.trackingNumber = order.getDelivery().getTrackingNumber();
        this.shipmentIssuedAt = order.getDelivery().getShipmentIssuedAt();
        this.totalPrice = order.getTotalPrice();
        this.orderDate = order.getOrderDate();
        Map<Long, Integer> quantities = order.quantitiesByProductId();
        this.items = order.getOrderItems().stream()
                .map(orderItem -> {
                    Long productId = orderItem.getProduct().getId();
                    boolean inboundRequired = order.getStatus() == OrderStatus.BACKORDERED
                            && availability.getOrDefault(productId, 0) < quantities.get(productId);
                    return new Item(productId, orderItem.getProduct().getName(),
                            orderItem.getCount(), inboundRequired);
                })
                .toList();
        this.shippable = order.getStatus() == OrderStatus.ORDER
                && order.getDelivery().getStatus() == DeliveryStatus.READY;
        this.deliverable = order.getDelivery().getStatus() == DeliveryStatus.SHIPPED;
        PaymentViewDto paymentView = PaymentViewDto.from(order, payment);
        this.orderStatusLabel = paymentView.orderStatusLabel();
        this.paymentStatusLabel = paymentView.paymentStatusLabel();
        this.allocationRetryable = order.getStatus() == OrderStatus.ALLOCATION_REVIEW;
        this.cancellationAllocationRetryable = cancellationAllocationRetryable;
    }

    public static AdminOrderDto from(Order order) {
        return from(order, Map.of());
    }

    public static AdminOrderDto from(Order order, Map<Long, Integer> availability) {
        return new AdminOrderDto(order, availability, null, false);
    }

    public static AdminOrderDto from(Order order, Map<Long, Integer> availability,
                                     Payment payment, boolean cancellationAllocationRetryable) {
        return new AdminOrderDto(order, availability, payment, cancellationAllocationRetryable);
    }

    public String getManagementGroup() {
        if (shippable) return "ready";
        if (deliverable) return "shipping";
        if (status == OrderStatus.BACKORDERED) return "backorder";
        if (deliveryStatus == DeliveryStatus.DELIVERED) return "completed";
        return "other";
    }
}
