package com.jhg.hgpage.wms.domain;

import com.jhg.hgpage.wms.domain.enums.PurchaseOrderStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrder {

    @Id @GeneratedValue
    @Column(name = "purchase_order_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    private PurchaseOrderStatus status;

    private String memo;

    private LocalDateTime createdAt;

    private LocalDateTime receivedAt;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL)
    private List<PurchaseOrderItem> items = new ArrayList<>();

    public static PurchaseOrder create(String memo, PurchaseOrderItem... items) {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.memo = memo;
        purchaseOrder.status = PurchaseOrderStatus.ORDERED;
        purchaseOrder.createdAt = LocalDateTime.now();
        for (PurchaseOrderItem item : items) {
            purchaseOrder.addItem(item);
        }
        return purchaseOrder;
    }

    private void addItem(PurchaseOrderItem item) {
        items.add(item);
        item.setPurchaseOrder(this);
    }

    /** 입고 처리: 모든 품목의 재고를 늘린다. 중복 입고는 재고 이중 증가이므로 거부한다. */
    public void receive() {
        if (status == PurchaseOrderStatus.RECEIVED) {
            throw new IllegalStateException("이미 입고 처리된 발주입니다. (발주 #" + id + ")");
        }

        items.forEach(item -> item.getProduct().addStock(item.getQuantity()));
        this.status = PurchaseOrderStatus.RECEIVED;
        this.receivedAt = LocalDateTime.now();
    }
}
