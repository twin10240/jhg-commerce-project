package com.jhg.hgpage.oms.repository;

import com.jhg.hgpage.oms.domain.Cart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CartRepository extends JpaRepository<Cart, Long> {

    Long countCartByMemberId(Long memberId);

    @Query(value = "select count(ci) from Cart c left outer join c.cartItems ci where c.member.id =:memberId")
    Long countCartItemByMemberId(Long memberId);

    Cart findCartByMemberId(Long memberId);
}
