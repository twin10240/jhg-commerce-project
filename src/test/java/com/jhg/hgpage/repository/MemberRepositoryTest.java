package com.jhg.hgpage.repository;

import com.jhg.hgpage.oms.repository.MemberRepository;
import com.jhg.hgpage.oms.domain.Account;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.domain.enums.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이메일로 회원 조회(`findMemberByEmail`, account inner join fetch) — 저장한 회원이 이메일로 조회되고
 * 매칭이 없으면 null을 반환한다.
 * (구 MemberRepositoryTest: 시드 이메일/ID(2L)에 의존하던 것을 자체 데이터로 교체)
 */
@DataJpaTest
class MemberRepositoryTest {

    @Autowired MemberRepository memberRepository;
    @Autowired TestEntityManager em;

    @Test
    void 이메일로_회원을_account와_조인해_조회한다() {
        Member member = Member.createUser("홍길동", "010-1234-5678", new Address("서울", "관악구", "500"));
        em.persist(member);
        em.persist(new Account("user@example.com", "hashed-password", member, Role.USER));
        em.flush();
        em.clear();

        Member found = memberRepository.findMemberByEmail("user@example.com");

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(member.getId());
        assertThat(found.getName()).isEqualTo("홍길동");
    }

    @Test
    void 없는_이메일은_null을_반환한다() {
        assertThat(memberRepository.findMemberByEmail("nobody@example.com")).isNull();
    }
}
