package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.dto.view.CartItemDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CartServiceTest {

    @Autowired MemberService memberService;
    @Autowired CartService cartService;

    @Test
    public void 카트아이템목록() throws Exception {
        List<Member> members = memberService.findMembers();
        List<Member> list = members.stream().filter(member -> !member.getName().equals("관리자")).collect(Collectors.toList());
        for (Member member : list) {
            assertThat(member.getName()).isNotEqualTo("관리자");

            List<CartItemDto> cartItems = cartService.findCartItemByMemberId(member.getId());
        }
    }

    @Test
    public void 장바구니목록테스트() throws Exception {
        cartService.addCartItem(2L, 1L, 1);
        cartService.addCartItem(2L, 3L, 3);
        cartService.addCartItem(2L, 5L, 5);

        List<CartItemDto> cartItems = cartService.findCartItemByMemberId(2L);
        assertThat(cartItems.size()).isEqualTo(3);

        int totalPrice = cartItems.stream().mapToInt(ci -> ci.getProductPrice()).sum();
        assertThat(totalPrice).isEqualTo(116000);
    }
}