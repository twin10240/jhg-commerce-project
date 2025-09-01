package com.jhg.hgpage.domain;

import jakarta.persistence.*;

import java.util.List;

@Entity
public class CartItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cart_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id")
    private Cart cart;

//    @OneToMany(fetch = FetchType.LAZY)
//    private List<Product> product;
}
