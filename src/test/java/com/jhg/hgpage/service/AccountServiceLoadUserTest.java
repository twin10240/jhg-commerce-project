package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.service.AccountService;

import com.jhg.hgpage.oms.domain.Account;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.oms.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceLoadUserTest {

    @Mock AccountRepository accountRepository;
    @InjectMocks AccountService accountService;

    @Test
    void 로그인시_Account에_연결된_Member로_Principal을_만든다() {
        Member member = Member.createUser("조형근", "010-0000-0000", new Address("서울", "관악구", "500"));
        Account account = new Account("user@example.com", "encoded-password", member, Role.USER);
        when(accountRepository.findByEmail("user@example.com")).thenReturn(Optional.of(account));

        UserDetails userDetails = accountService.loadUserByUsername("user@example.com");

        assertThat(userDetails.getUsername()).isEqualTo("조형근");
        assertThat(userDetails.getPassword()).isEqualTo("encoded-password");
    }
}
