package com.jhg.hgpage.oms.dto;

import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
public class OrderDetailDto {

    private final Long id;
    private final OrderStatus status;
    private final LocalDateTime orderDate;
    private final DeliveryStatus deliveryStatus;
    private final String city;
    private final String street;
    private final String zipcode;
    private final List<OrderLineDto> items;
    private final int totalPrice;
    // 취소 버튼 노출 조건: 주문 상태가 ORDER이고 아직 배송완료 전
    private final boolean cancelable;

    private OrderDetailDto(Order order) {
        this.id = order.getId();
        this.status = order.getStatus();
        this.orderDate = order.getOrderDate();
        this.deliveryStatus = order.getDelivery().getStatus();
        Address address = order.getDelivery().getAddress();
        this.city = address.getCity();
        this.street = address.getStreet();
        this.zipcode = address.getZipcode();
        this.items = order.getOrderItems().stream()
                .map(oi -> new OrderLineDto(oi.getProduct().getName(), oi.getOrderPrice(), oi.getCount(), oi.getTotalPrice()))
                .toList();
        this.totalPrice = order.getTotalPrice();
        // 백오더는 예약이 없어 자유롭게 취소 가능. 배송완료/이미취소만 불가.
        this.cancelable = (order.getStatus() == OrderStatus.ORDER || order.getStatus() == OrderStatus.BACKORDERED)
                && order.getDelivery().getStatus() != DeliveryStatus.COMP;
    }

    public static OrderDetailDto from(Order order) {
        return new OrderDetailDto(order);
    }

    @Getter
    public static class OrderLineDto {
        private final String productName;
        private final int orderPrice;
        private final int count;
        private final int totalPrice;

        public OrderLineDto(String productName, int orderPrice, int count, int totalPrice) {
            this.productName = productName;
            this.orderPrice = orderPrice;
            this.count = count;
            this.totalPrice = totalPrice;
        }
    }
}
