package com.jhg.hgpage.repositoey;

import com.jhg.hgpage.domain.Cart;
import com.jhg.hgpage.domain.Member;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CartRepositoryTest {

    @Autowired MemberRepository memberRepository;
    @Autowired CartRepository cartRepository;

    @Test
    public void 카운트테스트() throws Exception {
        Member member = memberRepository.findById(2L).get();

        Long count = cartRepository.countCartByMemberId(member.getId());

        assertThat(count).isEqualTo(1L);
    }

    @Test
    public void findCartByMemberIdTest() throws Exception {
        Cart cart = cartRepository.findCartByMemberId(2L);

        assertThat(cart.getId()).isEqualTo(1L);
    }

}