package com.jhg.hgpage.domain;

import com.jhg.hgpage.exception.NotEnoughStockException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import static jakarta.persistence.FetchType.LAZY;

@Entity
@Getter @Setter
public class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long id;
    private String name;
    private int price;

    @OneToOne(fetch = LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "inventory_id")
    private Inventory inventory;

    public void addStock(int quantity) {
        this.getInventory().addOnHandQty(quantity);
    }

    public void removeStock(int quantity) {
        int restStock = this.getInventory().getOnHandQty() - quantity;
        if (restStock < 0) throw new NotEnoughStockException("need more stock");

        this.getInventory().setOnHandQty(restStock);
    }
}
