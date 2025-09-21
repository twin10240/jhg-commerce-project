package com.jhg.hgpage.repositoey;

import com.jhg.hgpage.domain.Cart;
import com.jhg.hgpage.domain.dto.CartItemDto;
import com.jhg.hgpage.domain.dto.QCartItemDto;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;


import java.util.List;

import static com.jhg.hgpage.domain.QCart.cart;
import static com.jhg.hgpage.domain.QCartItem.cartItem;
import static com.jhg.hgpage.domain.QProduct.product;

@Repository
@RequiredArgsConstructor
public class CartRepositoryQuery {

    private final EntityManager em;

    private final JPAQueryFactory jpaQueryFactory;

    public int cartCount(Long memberId) {
        return em.createQuery("select count(*)" +
                                     " from CartItem ci" +
                                     " join ci.cart c" +
                                     " where c.member.id = :memberId", Integer.class).setParameter("memberId", memberId).getSingleResult();
    }

    public Long cartCount_QueryDsl(Long memberId) {
        return jpaQueryFactory.select(cartItem.count())
                              .from(cartItem)
                              .join(cartItem.cart, cart)
                              .where(cart.member.id.eq(memberId))
                              .fetchOne();
    }

    public List<Cart> findCartByMemberId(Long memberId) {
        return em.createQuery("select c" +
                                     " from Cart c" +
                                     " where c.member.id = :memberId", Cart.class).setParameter("memberId", memberId).getResultList();
    }

    public Long findItemCountMemberId_QueryDsl(Long memberId) {
        return jpaQueryFactory.select(cartItem.count())
                .from(cartItem)
                .join(cartItem.cart, cart)
                .where(cart.member.id.eq(memberId))
                .fetchOne();
    }

    public List<CartItemDto> findCartItemByMemberId(Long memberId) {
        return jpaQueryFactory.select(new QCartItemDto(cart.member.id, cart.id, product.id, product.name, cartItem.productPrice, cartItem.quantity))
                .from(cartItem)
                .join(cartItem.product, product)
                .join(cartItem.cart, cart)
                .where(cart.member.id.eq(memberId))
                .fetch();
    }

    public void save(Cart cart) {
        if (cart.getId() == null) {
            em.persist(cart);
        } else {
            em.merge(cart);
        }
    }
}
