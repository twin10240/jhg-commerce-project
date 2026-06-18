package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.service.AccountService;
import com.jhg.hgpage.oms.domain.Account;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.exception.DuplicateEmailException;
import com.jhg.hgpage.oms.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 회원가입 통합 테스트(임베디드 H2). 시드 데이터에 의존하지 않고
 * 테스트가 만든 데이터만 사용하며, 트랜잭션 롤백으로 DB를 더럽히지 않는다.
 */
@SpringBootTest
@Transactional
class AccountServiceTest {

    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;

    private Member newMember(String name) {
        return Member.createUser(name, "010-0000-0000", new Address("서울", "관악구", "500"));
    }

    @Test
    void 회원가입하면_Member와_Account가_함께_저장된다() {
        Member member = newMember("신규회원");

        accountService.signUp(member, new Account("signup-test@example.com", "encoded-password", member, Role.USER));

        Account saved = accountRepository.findByEmail("signup-test@example.com").orElseThrow();
        assertThat(saved.getMember().getName()).isEqualTo("신규회원");
        assertThat(saved.getMember().getCart()).isNotNull(); // 일반 회원은 장바구니 자동 생성
    }

    @Test
    void 이미_가입된_이메일로_가입하면_DuplicateEmailException을_던진다() {
        Member first = newMember("첫번째");
        accountService.signUp(first, new Account("dup-test@example.com", "pw", first, Role.USER));

        Member second = newMember("두번째");
        assertThatThrownBy(() -> accountService.signUp(second,
                new Account("dup-test@example.com", "pw", second, Role.USER)))
                .isInstanceOf(DuplicateEmailException.class);
    }
}
