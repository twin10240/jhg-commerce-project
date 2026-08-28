package com.jhg.hgpage.oms.dto;

import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Getter
public class OrderDetailDto {

    private final Long id;
    private final OrderStatus status;
    private final LocalDateTime orderDate;
    private final DeliveryStatus deliveryStatus;
    private final String carrierName;
    private final String trackingNumber;
    private final Instant shipmentIssuedAt;
    private final Instant deliveredAt;
    private final String city;
    private final String street;
    private final String zipcode;
    private final List<OrderLineDto> items;
    private final int totalPrice;
    // 취소 버튼 노출 조건: 주문 상태가 ORDER이고 아직 출고완료 전
    private final boolean cancelable;
    private final String orderStatusLabel;
    private final String paymentStatusLabel;
    private final int paidAmount;
    private final int pendingRefundAmount;
    private final int refundedAmount;
    private final boolean paymentRetryable;
    private final String refundStatusLabel;

    private OrderDetailDto(Order order, Payment payment) {
        this.id = order.getId();
        this.status = order.getStatus();
        this.orderDate = order.getOrderDate();
        this.deliveryStatus = order.getDelivery().getStatus();
        this.carrierName = order.getDelivery().getCarrierName();
        this.trackingNumber = order.getDelivery().getTrackingNumber();
        this.shipmentIssuedAt = order.getDelivery().getShipmentIssuedAt();
        this.deliveredAt = order.getDelivery().getDeliveredAt();
        Address address = order.getDelivery().getAddress();
        this.city = address.getCity();
        this.street = address.getStreet();
        this.zipcode = address.getZipcode();
        this.items = order.getOrderItems().stream()
                .map(oi -> new OrderLineDto(oi.getId(), oi.getProduct().getId(), oi.getProduct().getName(),
                        oi.getOrderPrice(), oi.getCount(), oi.getTotalPrice()))
                .toList();
        this.totalPrice = order.getTotalPrice();
        this.cancelable = order.getDelivery().getStatus() == DeliveryStatus.READY
                && order.getStatus() != OrderStatus.CANCEL
                && order.getStatus() != OrderStatus.CANCEL_REQUESTED;
        PaymentViewDto paymentView = PaymentViewDto.from(order, payment);
        this.orderStatusLabel = paymentView.orderStatusLabel();
        this.paymentStatusLabel = paymentView.paymentStatusLabel();
        this.paidAmount = paymentView.paidAmount();
        this.pendingRefundAmount = paymentView.pendingRefundAmount();
        this.refundedAmount = paymentView.refundedAmount();
        this.paymentRetryable = paymentView.paymentRetryable();
        this.refundStatusLabel = paymentView.refundStatusLabel();
    }

    public static OrderDetailDto from(Order order) {
        return new OrderDetailDto(order, null);
    }

    public static OrderDetailDto from(Order order, Payment payment) {
        return new OrderDetailDto(order, payment);
    }

    @Getter
    public static class OrderLineDto {
        private final Long orderItemId;
        private final Long productId;
        private final String productName;
        private final int orderPrice;
        private final int count;
        private final int totalPrice;

        public OrderLineDto(Long orderItemId, Long productId, String productName, int orderPrice, int count, int totalPrice) {
            this.orderItemId = orderItemId;
            this.productId = productId;
            this.productName = productName;
            this.orderPrice = orderPrice;
            this.count = count;
            this.totalPrice = totalPrice;
        }
    }
}
