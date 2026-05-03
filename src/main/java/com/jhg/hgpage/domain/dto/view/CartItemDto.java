package com.jhg.hgpage.domain.dto.view;

import com.querydsl.core.annotations.QueryProjection;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CartItemDto {

    private Long memberId;
    private Long cartId;
    private Long productId;
    private int idx;
    private String productName;
    private int productPrice;
    private int cartPrice;
    private int unitPrice;
    private int lineTotalPrice;
    private int quantity;

    @QueryProjection
    public CartItemDto(Long memberId, Long cartId, Long productId, String productName, int unitPrice, int quantity) {
        this.memberId = memberId;
        this.cartId = cartId;
        this.productId = productId;
        this.productName = productName;
        this.productPrice = unitPrice;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.lineTotalPrice = unitPrice * quantity;
        this.cartPrice = this.lineTotalPrice;
    }

    public int getTotalPrice() {
        return getUnitPrice() * getQuantity();
    }
}
