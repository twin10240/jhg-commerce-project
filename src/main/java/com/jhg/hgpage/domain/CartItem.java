package com.jhg.hgpage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Entity
@Getter @Setter
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

    public static CartItem createCartItem(Product product, int orderPrice, int count) {
        CartItem cartItem = new CartItem();
        cartItem.setProduct(product);
        cartItem.setProductPrice(orderPrice);
        cartItem.setQuantity(count);

        return cartItem;
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
