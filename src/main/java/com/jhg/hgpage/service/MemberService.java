package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Account;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.repositoey.AccountRepository;
import com.jhg.hgpage.repositoey.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {
    private final MemberRepository memberRepository;

    @Transactional
    public Long join(Member member){
        memberRepository.save(member);

        return member.getId();
    }
}
