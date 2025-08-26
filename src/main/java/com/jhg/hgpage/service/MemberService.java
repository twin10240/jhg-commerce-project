package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Account;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.repositoey.AccountRepository;
import com.jhg.hgpage.repositoey.MemberRepository;
import com.jhg.hgpage.repositoey.MemberRepositoryQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {
    private final MemberRepository memberRepository;
    private final MemberRepositoryQuery memberRepositoryQuery;

    @Transactional
    public Long join(Member member){
        memberRepository.save(member);

        return member.getId();
    }

    public Member findMember(Long id) {
        return memberRepository.findById(id).get();
    }

    public Member findMemberByEmail(String email) {
        return memberRepositoryQuery.findMemberByEmail(email);
    }
}
