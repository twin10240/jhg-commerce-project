package com.jhg.hgpage.repositoey;

import com.jhg.hgpage.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

public interface MemberRepository extends JpaRepository<Member, Long> {

    @Query(value = "select m from Member m inner join fetch m.account a where a.email =:email")
    Member findMemberByEmail(@Param("email") String email);
}