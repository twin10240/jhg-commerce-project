package com.jhg.hgpage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain web(HttpSecurity http) throws Exception {
        http
            // H2 콘솔 사용 시 sameOrigin, 그 외 프레임 보안 유지
            .headers(h -> h.frameOptions(f -> f.sameOrigin()))
            // CSRF 기본 활성화(폼 기반이라면 권장), 특정 경로만 예외 가능
            .csrf(csrf -> csrf.ignoringRequestMatchers("/h2-console/**", "/api/replenishments"))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/", "/login", "/signup", "/css/**", "/js/**", "/images/**", "/h2-console/**").permitAll()
                    // WMS 서버 간 콜백(재고 보충 통지) — 세션 없음
                    .requestMatchers("/api/replenishments").permitAll()
                    .requestMatchers("/admin/**").hasRole("ADMIN") // 내부적으로 "ROLE_ADMIN" 권한 검사
                    // 구매·장바구니는 고객(USER) 전용 — admin은 운영자라 주문 불가(운영자 ≠ 구매자)
                    .requestMatchers("/orders/**", "/cart/**", "/api/orders/**", "/api/cart/**").hasRole("USER")
                    .anyRequest().authenticated()
            )
            .formLogin(form -> form
                    .loginPage("/login")                 // 커스텀 로그인 페이지
                    .loginProcessingUrl("/login")        // POST 로그인 처리(기본값)
                    .usernameParameter("email")          // ★ 여기!
                    .passwordParameter("password")       // (선택) 기본값 "password"
                    .defaultSuccessUrl("/main", true)
                    .failureUrl("/login?error")
                    .permitAll()
            )
            .logout(logout -> logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/")
            );

        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12); // 작업부하(라운드) 조절
    }

}
