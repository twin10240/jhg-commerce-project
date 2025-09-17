package com.jhg.hgpage.repositoey;

import com.jhg.hgpage.domain.Member;
import jakarta.transaction.Transactional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class MemberRepositoryTest {

    @Autowired MemberRepository memberRepository;

    @Test
    public void memberRepositoryTest() throws Exception {
        Member member = memberRepository.findMemberByEmail("twin10240@naver.com");

        Assertions.assertThat(member.getName()).isEqualTo("조형근");
        Assertions.assertThat(member.getId()).isEqualTo(2L);
    }

}