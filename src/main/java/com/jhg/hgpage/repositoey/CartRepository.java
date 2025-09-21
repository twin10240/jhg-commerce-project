package com.jhg.hgpage.repositoey;

import com.jhg.hgpage.domain.Cart;
import com.jhg.hgpage.domain.dto.CartItemDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CartRepository extends JpaRepository<Cart, Long> {

    Long countCartByMemberId(Long memberId);

    @Query(value = "select count(ci) from Cart c left outer join c.cartItems ci where c.member.id =:memberId")
    Long countCartItemByMemberId(Long memberId);

    Cart findCartByMemberId(Long memberId);
}
