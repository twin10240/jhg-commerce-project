package com.jhg.hgpage.catalog;

import com.jhg.hgpage.oms.domain.CartItem;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter @Setter
public class Product {
    @Id @GeneratedValue
    @Column(name = "product_id")
    private Long id;
    private String name;
    private int price;

    @OneToMany(mappedBy = "product")
    private List<CartItem> cartItems = new ArrayList<>();
}
