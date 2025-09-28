package com.jhg.hgpage.domain.dto.view;

import com.querydsl.core.annotations.QueryProjection;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CartItemDto {

    private Long memberId;
    private Long cartId;
    private Long productId;
    private int idx;
    private String productName;
    private int productPrice;
    private int cartPrice;
    private int quantity;

    @QueryProjection
    public CartItemDto(Long memberId, Long cartId, Long productId, String productName, int productPrice, int quantity) {
        this.memberId = memberId;
        this.cartId = cartId;
        this.productId = productId;
        this.productName = productName;
        this.productPrice = productPrice;
        this.quantity = quantity;
    }

    public CartItemDto(Long memberId, Long cartId, Long productId, int idx, String productName, int cartPrice, int productPrice, int quantity) {
        this.memberId = memberId;
        this.cartId = cartId;
        this.productId = productId;
        this.idx = idx;
        this.productName = productName;
        this.cartPrice = cartPrice;
        this.productPrice = productPrice;
        this.quantity = quantity;
    }

    public int getTotalPrice() {
        return getProductPrice() * getQuantity();
    }
}
