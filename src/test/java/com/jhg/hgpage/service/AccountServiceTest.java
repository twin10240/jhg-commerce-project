package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Account;
import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Member;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class AccountServiceTest {
    @Autowired
    AccountService accountService;

    @Autowired
    MemberService memberService;

    @Test
    @Rollback(value = false)
    public void SignupAndJoinTest() {
        Member member = new Member("조형근", "010-6797-5587", new Address("서울", "관악구", "봉천동"));
        memberService.join(member);

        Account account = new Account();
        account.createAccount("twin10240@naver.com", "1111", member);
        accountService.signUp(account);

        Member member2 = new Member("이동호", "010-6797-5587", new Address("서울", "관악구", "봉천동"));
        memberService.join(member2);

        Account account2 = new Account();
        account2.createAccount("twin1024@naver.com", "1111", member2);
        accountService.signUp(account2);
    }

}