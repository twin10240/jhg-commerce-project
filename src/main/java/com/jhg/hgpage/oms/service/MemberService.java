package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.oms.domain.Cart;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.repository.MemberRepository;
import com.jhg.hgpage.oms.repository.MemberRepositoryQuery;
import jakarta.persistence.NoResultException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    public List<Member> findMembers(){
        return memberRepository.findAll();
    }

    public Member findMember(Long id) {
        return memberRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Member", id));
    }

    public Member findMemberByEmail(String email) throws NoResultException {
        return memberRepository.findMemberByEmail(email);
    }

    public Member findMemberByEmailWithQueryDsl(String email) throws NoResultException {
        return memberRepositoryQuery.findMemberByEmailWithQueryDsl(email);
    }
}
