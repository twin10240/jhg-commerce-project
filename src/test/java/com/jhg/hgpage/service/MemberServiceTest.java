package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Cart;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.repositoey.MemberRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MemberServiceTest {

    @Autowired MemberRepository memberRepository;

    @Test
    public void 회원등록테스트() throws Exception {
        Member member = new Member("조형근", "010", new Address("a", "b", "c"));
        member.setCart(new Cart(member));

        Member result = memberRepository.save(member);

        assertThat(result).isEqualTo(member);
    }

}