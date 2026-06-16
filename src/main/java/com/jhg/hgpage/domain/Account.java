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

    // ID 생성 전략은 프로젝트 전체를 AUTO(시퀀스)로 통일한다(#12)
    @Id @GeneratedValue
    private Long id;
    // unique 제약은 @Table의 명명된 인덱스(ux_account_email)로만 선언
    @Column(nullable=false, length=190)
    private String email;
    @Column(nullable = false, length = 100) // bcrypt 60자 + 여유
    private String password; // 해시 저장
    @Enumerated(EnumType.STRING)
    @Column(nullable=false, length=20)
    private Role role = Role.USER;
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", unique = true)
    private Member member;

    public Account(String email, String password, Member member, Role role) {
        this.email = email;
        this.password = password;
        this.member = member;
        this.role = role;
    }
}
