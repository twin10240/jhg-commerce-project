package com.jhg.hgpage.repositoey;

import com.jhg.hgpage.domain.Member;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MemberRepositoryQuery {

    private final EntityManager em;

    public Member findMemberByEmail(String email) {
        return em.createQuery("select m from Account a join a.member m where a.email =: email", Member.class).setParameter("email", email).getSingleResult();
    }
}
