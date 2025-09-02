package com.jhg.hgpage.repositoey;

import com.jhg.hgpage.domain.Cart;
import com.jhg.hgpage.domain.CartItem;
import com.jhg.hgpage.domain.Product;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


import static com.jhg.hgpage.domain.QCart.cart;
import static com.jhg.hgpage.domain.QCartItem.cartItem;

@Repository
@RequiredArgsConstructor
public class CartRepository {

    private final EntityManager em;

    private final JPAQueryFactory jpaQueryFactory;

    private final ProductRepository productRepository;

    public int CartCount(Long memberId) {
        return em.createQuery("select count(*)" +
                                     " from CartItem ci" +
                                     " join ci.cart c" +
                                     " where c.member.id = :memberId", Integer.class).setParameter("memberId", memberId).getSingleResult();
    }

    public Long CartCountWithQueryDsl(Long memberId) {
        return jpaQueryFactory.select(cartItem.count())
                              .from(cartItem)
                              .join(cartItem.cart, cart)
                              .where(cart.member.id.eq(memberId))
                              .fetchOne();
    }

    public void save(Cart cart) {
        if (cart.getId() == null) {
            em.persist(cart);
        } else {
            em.merge(cart);
        }
    }
}
