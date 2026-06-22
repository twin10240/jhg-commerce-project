package com.jhg.hgpage.oms.dto;

import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class AdminOrderDto {

    private final Long id;
    private final String memberName;
    private final OrderStatus status;
    private final DeliveryStatus deliveryStatus;
    private final int totalPrice;
    private final LocalDateTime orderDate;
    // 배송완료 버튼 노출 조건: 진행 중(ORDER) + 아직 배송 전(READY)
    private final boolean completable;

    private AdminOrderDto(Order order) {
        this.id = order.getId();
        this.memberName = order.getMember().getName();
        this.status = order.getStatus();
        this.deliveryStatus = order.getDelivery().getStatus();
        this.totalPrice = order.getTotalPrice();
        this.orderDate = order.getOrderDate();
        this.completable = order.getStatus() == OrderStatus.ORDER
                && order.getDelivery().getStatus() == DeliveryStatus.READY;
    }

    public static AdminOrderDto from(Order order) {
        return new AdminOrderDto(order);
    }
}
