package com.jhg.hgpage.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cart_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", unique = true, nullable = false)
    private Member member;

    @OneToMany(mappedBy = "cart", fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.REMOVE}, orphanRemoval = true)
    private List<CartItem> cartItems = new ArrayList<>();

    public CartItem addCartItem(Product product, int quantity, int productPrice) {
        // 1) 이미 담긴 상품이면 수량만 증가
        for (CartItem it : cartItems) {
            if (it.isSameProduct(product)) {
                it.addQuantity(quantity);

                return it;
            }
        }

        // 2) 새 아이템 추가
        CartItem newItem = CartItem.createCartItem(product, productPrice, quantity);
        newItem.attachTo(this);       // 양방향 일관성
        cartItems.add(newItem);
        return newItem;
    }

    public void removeItem(Product product) {
        cartItems.removeIf(cartItem -> {
            boolean match = cartItem.isSameProduct(product);
            if (match) cartItem.detach();   // 선택: 반대편 참조 정리
            return match;
        });
    }

    public Cart(Member member) {
        this.member = member;
    }

    public static Cart createCart(Member member) {
        Cart cart = new Cart();
        cart.setMember(member);

        return cart;
    }
}
