package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Account;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.exception.DuplicateEmailException;
import com.jhg.hgpage.repository.AccountRepository;
import com.jhg.hgpage.repository.MemberRepository;
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
    private final MemberRepository memberRepository;

    /**
     * 회원가입 유스케이스. Member와 Account를 하나의 트랜잭션으로 저장해
     * Account 저장 실패 시 Member(+Cart)만 남는 고아 데이터를 방지한다.
     */
    @Transactional
    public Long signUp(Member member, Account account) {
        if (accountRepository.existsByEmail(account.getEmail())) {
            throw new DuplicateEmailException(account.getEmail());
        }

        memberRepository.save(member);
        accountRepository.save(account);

        return account.getId();
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        Account account = accountRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Email does not exist."));

        return UserPrincipal.from(account, account.getMember());
    }
}
