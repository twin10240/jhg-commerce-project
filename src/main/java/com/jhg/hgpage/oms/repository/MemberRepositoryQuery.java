package com.jhg.hgpage.oms.repository;

import com.jhg.hgpage.oms.domain.Member;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import static com.jhg.hgpage.oms.domain.QAccount.account;
import static com.jhg.hgpage.oms.domain.QMember.member;


@Repository
@RequiredArgsConstructor
public class MemberRepositoryQuery {

    private final EntityManager em;

    private final JPAQueryFactory jpaQueryFactory;

    public Member findMemberByEmailWithQueryDsl(String email) {
        return jpaQueryFactory.select(member)
                              .from(account)
                              .join(account.member, member)
                              .where(account.email.eq(email)).fetchOne();
    }
}
