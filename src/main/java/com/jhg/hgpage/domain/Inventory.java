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
    @Column(nullable=false)
    private int onHandQty = 0; // 현재 보유 수량
    @Column(nullable=false)
    private int reservedQty = 0; // 예약(결제 진행 중 등)
    @OneToOne(mappedBy = "inventory", fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

}
