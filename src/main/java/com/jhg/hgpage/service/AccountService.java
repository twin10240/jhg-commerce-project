package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Account;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.repositoey.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountService implements UserDetailsService {
    private final AccountRepository accountRepository;
    private final MemberService memberService;

    @Transactional
    public Long signUp(Account account) {
        if(accountRepository.existsByEmail(account.getEmail())) {
            throw new IllegalArgumentException("이미 가입된 이메일입니다.");
        }

        accountRepository.save(account);

        return account.getId();
    }

    public Account findAccountByEmail(String email) {
        return accountRepository.findByEmail(email).get();
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        Account account = accountRepository.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("존재하지 않는 이메일입니다."));
        Member member = memberService.findMember(account.getId());

        return UserPrincipal.from(account, member);
    }
}
