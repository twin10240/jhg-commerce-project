package com.jhg.hgpage.domain.dto;

import com.jhg.hgpage.domain.Account;
import com.jhg.hgpage.domain.enums.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public class UserPrincipal implements UserDetails {
    private final Long id;
    private final String email;
    private final String password;
    private final Role role;

    public UserPrincipal(Long id, String email, String password, Role role) {
        this.id = id;
        this.email = email;
        this.password = password;
        this.role = role;
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        // hasRole('ADMIN')와 호환되도록 ROLE_ 접두사 부여
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return email;
    }

    public Long getId() {
        return id;
    }

    // 팩토리 메서드
    public static UserPrincipal from(Account account) {
        Role role = account.getRole();

        return new UserPrincipal(account.getId(), account.getEmail(), account.getPassword(), role);
    }

}
