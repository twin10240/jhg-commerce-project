package com.jhg.hgpage.domain;

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
}
