package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Account;
import com.jhg.hgpage.repositoey.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountService {
    private final AccountRepository accountRepository;

    @Transactional
    public Long signUp(Account account){
        if(accountRepository.existsByEmail(account.getEmail())) {
            throw new IllegalArgumentException("이미 가입된 이메일입니다.");
        }

        accountRepository.save(account);

        return account.getId();
    }
}
