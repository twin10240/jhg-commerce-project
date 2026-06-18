package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.service.AccountService;
import com.jhg.hgpage.oms.domain.Account;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.exception.DuplicateEmailException;
import com.jhg.hgpage.oms.repository.AccountRepository;
import com.jhg.hgpage.oms.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceSignUpTest {

    @Mock AccountRepository accountRepository;
    @Mock MemberRepository memberRepository;
    @InjectMocks AccountService accountService;

    private Member member() {
        return Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
    }

    private Account account(Member member) {
        return new Account("user@example.com", "encoded-password", member, Role.USER);
    }

    @Test
    void 회원가입하면_Member와_Account를_모두_저장한다() {
        Member member = member();
        Account account = account(member);
        when(accountRepository.existsByEmail("user@example.com")).thenReturn(false);

        accountService.signUp(member, account);

        verify(memberRepository).save(member);
        verify(accountRepository).save(account);
    }

    @Test
    void 이메일이_중복이면_DuplicateEmailException을_던지고_아무것도_저장하지_않는다() {
        Member member = member();
        Account account = account(member);
        when(accountRepository.existsByEmail("user@example.com")).thenReturn(true);

        assertThatThrownBy(() -> accountService.signUp(member, account))
                .isInstanceOf(DuplicateEmailException.class);

        verify(memberRepository, never()).save(any(Member.class));
        verify(accountRepository, never()).save(any(Account.class));
    }
}
