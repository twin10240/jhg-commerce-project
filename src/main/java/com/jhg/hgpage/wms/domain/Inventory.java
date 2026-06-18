package com.jhg.hgpage.wms.domain;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.exception.NotEnoughStockException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter @Setter
public class Inventory {
    @Id @GeneratedValue
    @Column(name = "inventory_id")
    private Long id;
    // 낙관적 락: 동시 재고 수정 시 늦게 커밋하는 쪽이 OptimisticLockException으로 실패한다(오버셀 방지)
    @Version
    private Long version;
    @Column(nullable=false)
    private int onHandQty = 0; // 현재 보유 수량
    @Column(nullable=false)
    private int reservedQty = 0; // 예약(결제 진행 중 등)
    @OneToOne(mappedBy = "inventory", fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    public void addOnHandQty(int quantity) {
        this.onHandQty += quantity;
    }

    /** 판매 가용 수량 = 실물(onHand) - 예약(reserved) */
    public int getAvailableQty() {
        return onHandQty - reservedQty;
    }

    /** 주문 접수 시 가용분을 예약한다. 실물은 출고(ship) 시점에 차감된다. */
    public void reserve(int quantity) {
        requirePositive(quantity);
        if (quantity > getAvailableQty()) {
            throw new NotEnoughStockException("need more stock");
        }
        this.reservedQty += quantity;
    }

    /** 주문 취소 시 예약을 해제한다. */
    public void release(int quantity) {
        requirePositive(quantity);
        if (quantity > reservedQty) {
            throw new IllegalStateException("예약된 수량보다 많이 해제할 수 없습니다.");
        }
        this.reservedQty -= quantity;
    }

    /** 출고 — 예약을 소진하면서 실물을 차감한다. */
    public void ship(int quantity) {
        requirePositive(quantity);
        if (quantity > reservedQty) {
            throw new IllegalStateException("예약된 수량보다 많이 출고할 수 없습니다.");
        }
        this.reservedQty -= quantity;
        this.onHandQty -= quantity;
    }

    private void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("수량은 1 이상이어야 합니다.");
        }
    }
}
