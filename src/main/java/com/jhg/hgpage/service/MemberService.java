package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Cart;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.repositoey.MemberRepository;
import com.jhg.hgpage.repositoey.MemberRepositoryQuery;
import jakarta.persistence.NoResultException;
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
        member.setCart(Cart.createCart(member));

        memberRepository.save(member);

        return member.getId();
    }

    public Member findMember(Long id) {
        return memberRepository.findById(id).get();
    }

    public Member findMemberByEmail(String email) throws NoResultException {
        return memberRepository.findMemberByEmail(email);
    }

    public Member findMemberByEmailWithQueryDsl(String email) throws NoResultException {
        return memberRepositoryQuery.findMemberByEmailWithQueryDsl(email);
    }

    public Member findById(Long id) {
        return memberRepository.findById(id).get();
    }
}
