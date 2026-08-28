package com.jhg.hgpage.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.Assert;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
public class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain replenishmentCallback(
            HttpSecurity http,
            @Value("${oms.callback.user:wms}") String user,
            @Value("${oms.callback.password:wms}") String password) throws Exception {
        Assert.hasText(user, "OMS callback user must not be blank");
        Assert.hasText(password, "OMS callback password must not be blank");
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        UserDetailsService users = new InMemoryUserDetailsManager(
                User.withUsername(user).password(encoder.encode(password)).roles("WMS").build());
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);

        http.securityMatcher("/api/replenishments", "/api/return-status-events", "/api/delivery-events")
                .csrf(AbstractHttpConfigurer::disable)
                .authenticationProvider(provider)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .httpBasic(withDefaults());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain web(HttpSecurity http) throws Exception {
        http
            // H2 콘솔 사용 시 sameOrigin, 그 외 프레임 보안 유지
            .headers(h -> h.frameOptions(f -> f.sameOrigin()))
            // CSRF 기본 활성화(폼 기반이라면 권장), 특정 경로만 예외 가능
            .csrf(csrf -> csrf.ignoringRequestMatchers("/h2-console/**"))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/", "/login", "/signup", "/error", "/css/**", "/js/**", "/images/**", "/h2-console/**").permitAll()
                    .requestMatchers("/admin/**").hasRole("ADMIN") // 내부적으로 "ROLE_ADMIN" 권한 검사
                    // 주문·반품·장바구니는 고객(USER) 전용 — admin은 운영자라 구매 흐름 사용 불가
                    .requestMatchers("/orders/**", "/returns/**", "/cart/**", "/api/cart/**").hasRole("USER")
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
