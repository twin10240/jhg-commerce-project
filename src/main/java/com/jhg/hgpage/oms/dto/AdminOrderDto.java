package com.jhg.hgpage.oms.dto;

import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
public class AdminOrderDto {

    private final Long id;
    private final String memberName;
    private final OrderStatus status;
    private final DeliveryStatus deliveryStatus;
    private final int totalPrice;
    private final LocalDateTime orderDate;
    private final List<Item> items;
    private final boolean shippable;
    private final boolean deliverable;

    public record Item(Long productId, String productName, int quantity, boolean inboundRequired) {}

    private AdminOrderDto(Order order, Map<Long, Integer> availability) {
        this.id = order.getId();
        this.memberName = order.getMember().getName();
        this.status = order.getStatus();
        this.deliveryStatus = order.getDelivery().getStatus();
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
    }

    public static AdminOrderDto from(Order order) {
        return from(order, Map.of());
    }

    public static AdminOrderDto from(Order order, Map<Long, Integer> availability) {
        return new AdminOrderDto(order, availability);
    }
}
