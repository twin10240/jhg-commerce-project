package com.jhg.hgpage.domain;

import com.jhg.hgpage.domain.enums.DeliveryStatus;
import com.jhg.hgpage.domain.enums.OrderStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static jakarta.persistence.FetchType.LAZY;

@Entity
@Getter @Setter
@Table(name = "orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id @GeneratedValue
    @Column(name = "order_id")
    private Long id;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<OrderItem> orderItems = new ArrayList<OrderItem>();

    @OneToOne(fetch = LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "delivery_id")
    private Delivery delivery;

    private LocalDateTime orderDate;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    public void setMember(Member member) {
        this.member = member;
        member.getOrders().add(this);
    }

    public void addOrderItem(OrderItem orderItem) {
        this.orderItems.add(orderItem);
        orderItem.setOrder(this);
    }

    public void setDelivery(Delivery delivery) {
        this.delivery = delivery;
        delivery.setOrder(this);
    }

    public static Order createOrder(Member member, Delivery delivery, OrderItem... orderItems) {
        Order order = new Order();

        order.setMember(member);
        order.setDelivery(delivery);
        // 배송 상태를 초기화해야 cancel()의 "배송완료 시 취소 불가" 가드가 동작한다
        delivery.setStatus(DeliveryStatus.READY);

        for (OrderItem orderItem: orderItems) {
            order.addOrderItem(orderItem);
        }
        order.setStatus(OrderStatus.ORDER);
        order.setOrderDate(LocalDateTime.now());

        return order;
    }

    /**
     * 재고 할당 (전부-아니면-백오더): 모든 라인이 가용하면 예약하고 ORDER,
     * 하나라도 부족하면 아무것도 예약하지 않고 BACKORDERED로 접수한다.
     * 입고 후 백오더 재할당(승격)에도 같은 메서드를 사용한다.
     */
    public void allocate() {
        boolean allAvailable = orderItems.stream()
                .allMatch(oi -> oi.getProduct().getInventory().getAvailableQty() >= oi.getCount());
        if (!allAvailable) {
            this.status = OrderStatus.BACKORDERED;
            return;
        }
        for (OrderItem orderItem : orderItems) {
            orderItem.getProduct().getInventory().reserve(orderItem.getCount());
        }
        this.status = OrderStatus.ORDER;
    }

    public void cancel() {
        if(delivery.getStatus() == DeliveryStatus.COMP){
            throw new IllegalStateException("이미 배송완료된 상품은 취소가 불가능합니다.");
        }
        // 재취소를 막지 않으면 예약이 이중 해제된다
        if(this.status == OrderStatus.CANCEL){
            throw new IllegalStateException("이미 취소된 주문입니다.");
        }

        // 예약은 ORDER 상태에만 존재한다. BACKORDERED는 해제할 예약이 없다.
        if (this.status == OrderStatus.ORDER) {
            for (OrderItem orderItem : orderItems) {
                orderItem.getProduct().getInventory().release(orderItem.getCount());
            }
        }
        this.setStatus(OrderStatus.CANCEL);
    }

    // 관리자 출고(배송완료) 처리 — 상태 전이만 담당한다.
    // 실물 재고 차감(ship)은 서비스 계층이 InventoryPort(WMS)에 위임한다(객체 그래프 결합 제거).
    public void completeDelivery() {
        if (this.status == OrderStatus.CANCEL) {
            throw new IllegalStateException("취소된 주문은 배송완료 처리할 수 없습니다.");
        }
        if (this.status == OrderStatus.BACKORDERED) {
            throw new IllegalStateException("입고 대기(백오더) 주문은 출고할 수 없습니다.");
        }
        if (delivery.getStatus() == DeliveryStatus.COMP) {
            throw new IllegalStateException("이미 배송완료된 주문입니다.");
        }
        delivery.setStatus(DeliveryStatus.COMP);
    }

    public int getTotalPrice() {
        return orderItems.stream().mapToInt(OrderItem::getTotalPrice).sum();
    }


}
