package com.jhg.hgpage.domain;

import com.jhg.hgpage.domain.enums.Role;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(indexes = @Index(name="ux_account_email", columnList="email", unique=true))
public class Account {

    // IDENTITY 전략은 em.persist()로 객체를 영속화 시키는 시점에 곧바로 insert 쿼리가 DB로 전송되고, 거기서 반환받은 식별자 값을 가지고 1차 캐시에 엔티티를 등록시켜 관리
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable=false, length=190, unique=true)
    private String email;
    @Column(nullable = false, length = 100) // bcrypt 60자 + 여유
    private String password; // 해시 저장
    @Enumerated(EnumType.STRING)
    @Column(nullable=false, length=20)
    private Role role = Role.USER;
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", unique = true)
    private Member member;
    Boolean enabled = true;

    public Role getRole() {
        return role;
    }

    public Account(String email, String password, Member member) {
        this.email = email;
        this.password = password;
        this.member = member;
    }

    public void setRole(Role role) {
        this.role = Role.ADMIN;
    }

    public static Account createAdminAccount(String email, String password, Member member) {
        Account account = new Account(email, password, member);
        account.setRole(Role.ADMIN);

        return account;
    }
}
