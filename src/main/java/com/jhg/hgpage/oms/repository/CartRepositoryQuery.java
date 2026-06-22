package com.jhg.hgpage.oms.repository;

import com.jhg.hgpage.oms.dto.CartItemDto;
import com.jhg.hgpage.oms.dto.QCartItemDto;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.jhg.hgpage.oms.domain.QCart.cart;
import static com.jhg.hgpage.oms.domain.QCartItem.cartItem;
import static com.jhg.hgpage.catalog.QProduct.product;

@Repository
@RequiredArgsConstructor
public class CartRepositoryQuery {

    private final JPAQueryFactory jpaQueryFactory;

    public List<CartItemDto> findCartItemByMemberId(Long memberId) {
        return jpaQueryFactory.select(new QCartItemDto(cart.member.id, cart.id, product.id, product.name, product.price, cartItem.quantity))
                .from(cartItem)
                .join(cartItem.product, product)
                .join(cartItem.cart, cart)
                .where(cart.member.id.eq(memberId))
                .fetch();
    }
}
