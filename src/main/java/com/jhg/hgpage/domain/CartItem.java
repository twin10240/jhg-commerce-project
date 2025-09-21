package com.jhg.hgpage.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cart_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id")
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    private int productPrice;
    private int quantity;

    public CartItem(Product product, int productPrice, int quantity) {
        this.product = product;
        this.productPrice = productPrice;
        this.quantity = quantity;
    }

    public static CartItem createCartItem(Product product, int orderPrice, int count) {
        return new CartItem(product, orderPrice, count);
    }

    public int getTotalPice() {
        return getProductPrice() * getQuantity();
    }

    public boolean isSameProduct(Product product) {
        return this.product.equals(product);
    }

    public void addQuantity(int quantity) {
        this.quantity += quantity;
    }

    public void attachTo(Cart cart) {
        this.cart = cart;
    }

    public void detach() {
        this.cart = null;
    }
}
