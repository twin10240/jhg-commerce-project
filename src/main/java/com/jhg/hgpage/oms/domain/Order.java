package com.jhg.hgpage.oms.domain;

import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    // 취소↔백오더 승격 경합(리뷰 B5) 등 동시 상태 전이 충돌을 막기 위한 낙관적 락.
    @Version
    private Long version;

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

    // 할당 결과 상태 전이 — 예약/가용성 판정은 서비스(OrderAllocationService)가 InventoryPort로 수행하고,
    // 그 결과(전 라인 예약 성공 여부)에 따라 아래 둘 중 하나로 표시한다.
    public void markOrdered() {
        this.status = OrderStatus.ORDER;
    }

    public void markBackordered() {
        this.status = OrderStatus.BACKORDERED;
    }

    /** 주문 라인을 상품 id→수량 맵으로 집계한다(같은 상품 중복 라인은 합산). 재고 연산(예약/해제/출고)의 입력. */
    public Map<Long, Integer> quantitiesByProductId() {
        return orderItems.stream()
                .collect(Collectors.groupingBy(
                        orderItem -> orderItem.getProduct().getId(),
                        Collectors.summingInt(OrderItem::getCount)));
    }

    // 취소는 상태 전이만 담당한다. 예약 해제(release)는 서비스 계층이 InventoryPort(WMS)에 위임한다.
    // 예약은 ORDER 상태에만 존재하므로, 해제가 필요한지(취소 직전이 ORDER였는지)는 서비스가 판단한다.
    public void cancel() {
        if(delivery.getStatus() == DeliveryStatus.COMP){
            throw new IllegalStateException("이미 배송완료된 상품은 취소가 불가능합니다.");
        }
        // 재취소를 막지 않으면 예약이 이중 해제된다
        if(this.status == OrderStatus.CANCEL){
            throw new IllegalStateException("이미 취소된 주문입니다.");
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
