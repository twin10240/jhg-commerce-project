package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Cart;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MemberServiceTest {

    @Autowired MemberRepository memberRepository;

    @Test
    public void joinTest() throws Exception {
        Member member = new Member("member", "010", new Address("a", "b", "c"));
        member.createCart(new Cart(member));

        Member result = memberRepository.save(member);

        assertThat(result).isEqualTo(member);
    }

}
