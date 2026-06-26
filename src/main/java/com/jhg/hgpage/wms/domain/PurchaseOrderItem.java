package com.jhg.hgpage.wms.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static jakarta.persistence.FetchType.LAZY;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrderItem {

    @Id @GeneratedValue
    @Column(name = "purchase_order_item_id")
    private Long id;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "purchase_order_id")
    private PurchaseOrder purchaseOrder;

    // WMS는 상품을 productId로만 참조한다(catalog 객체그래프 없음).
    @Column(name = "product_id")
    private Long productId;

    private int quantity;

    public static PurchaseOrderItem create(Long productId, int quantity) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.productId = productId;
        item.quantity = quantity;
        return item;
    }

    void setPurchaseOrder(PurchaseOrder purchaseOrder) {
        this.purchaseOrder = purchaseOrder;
    }
}
