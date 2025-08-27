package com.jhg.hgpage.repositoey;

import com.jhg.hgpage.domain.Member;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import static com.jhg.hgpage.domain.QAccount.account;
import static com.jhg.hgpage.domain.QMember.member;


@Repository
@RequiredArgsConstructor
public class MemberRepositoryQuery {

    private final EntityManager em;

    private final JPAQueryFactory jpaQueryFactory;

    public Member findMemberByEmail(String email) {
        return em.createQuery("select m from Account a join a.member m where a.email =: email", Member.class).setParameter("email", email).getSingleResult();
    }

    public Member findMemberByEmailWithQueryDsl(String email) {
        return jpaQueryFactory.select(member)
                              .from(account)
                              .join(account.member, member)
                              .where(account.email.eq(email)).fetchOne();
    }
}
