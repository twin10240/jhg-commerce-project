package com.jhg.hgpage.repository;

import com.jhg.hgpage.oms.repository.CartRepository;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Cart;
import com.jhg.hgpage.oms.domain.Member;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회원 → 장바구니(1:1, cascade) 조회 — 회원을 저장하면 장바구니가 함께 생성되고,
 * 회원 id로 개수/장바구니를 조회할 수 있다.
 * (구 CartRepositoryTest: 시드 ID(2L)·장바구니 ID(1L)에 의존하던 것을 자체 데이터로 교체)
 */
@DataJpaTest
class CartRepositoryTest {

    @Autowired CartRepository cartRepository;
    @Autowired TestEntityManager em;

    private static final Address ADDRESS = new Address("서울", "관악구", "500");

    private Member persistUserWithCart() {
        Member member = Member.createUser("테스터", "010-0000-0000", ADDRESS);
        em.persist(member); // Member → Cart cascade
        em.flush();
        return member;
    }

    @Test
    void 회원의_장바구니_개수를_센다() {
        Member member = persistUserWithCart();
        em.clear();

        assertThat(cartRepository.countCartByMemberId(member.getId())).isEqualTo(1L);
    }

    @Test
    void 회원_id로_장바구니를_조회한다() {
        Member member = persistUserWithCart();
        Long cartId = member.getCart().getId();
        em.clear();

        Cart found = cartRepository.findCartByMemberId(member.getId());

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(cartId);
    }
}
