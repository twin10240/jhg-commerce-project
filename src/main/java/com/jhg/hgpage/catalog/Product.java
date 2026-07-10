package com.jhg.hgpage.catalog;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter @Setter
public class Product {
    @Id @GeneratedValue
    @Column(name = "product_id")
    private Long id;
    private String name;
    private int price;
}
