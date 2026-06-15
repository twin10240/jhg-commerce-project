package com.jhg.hgpage.domain.dto.view;

import com.querydsl.core.annotations.QueryProjection;
import lombok.Getter;

@Getter
public class CartItemDto {

    private Long memberId;
    private Long cartId;
    private Long productId;
    private String productName;
    private int unitPrice;
    private int lineTotalPrice;
    private int quantity;

    @QueryProjection
    public CartItemDto(Long memberId, Long cartId, Long productId, String productName, int unitPrice, int quantity) {
        this.memberId = memberId;
        this.cartId = cartId;
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.lineTotalPrice = unitPrice * quantity;
    }
}
