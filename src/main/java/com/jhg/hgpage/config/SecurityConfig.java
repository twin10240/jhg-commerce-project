package com.jhg.hgpage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12); // 작업부하(라운드) 조절
    }

//    @Bean
//    DaoAuthenticationProvider authProvider(CustomUserDetailsService uds, PasswordEncoder pe) {
//        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
//        p.setUserDetailsService(uds);   // ← 사용자 조회 전략 (DB 등)
//        p.setPasswordEncoder(pe);       // ← 비번 검증 전략 (bcrypt/argon2 등)
//        return p;                       // ← AuthenticationManager가 사용
//    }
}
