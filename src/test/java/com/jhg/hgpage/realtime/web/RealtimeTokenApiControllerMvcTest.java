package com.jhg.hgpage.realtime.web;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.realtime.auth.RealtimeJwtProperties;
import com.jhg.hgpage.realtime.auth.RealtimeTokenService;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RealtimeTokenApiController.class)
@Import({SecurityConfig.class, RealtimeTokenService.class, RealtimeTokenApiControllerMvcTest.TestConfig.class})
class RealtimeTokenApiControllerMvcTest {

    @Autowired MockMvc mockMvc;
    @Autowired RealtimeTokenApiController controller;
    @Autowired RealtimeTokenService tokenService;

    @AfterEach
    void restoreTokenService() {
        ReflectionTestUtils.setField(controller, "tokenService", tokenService);
    }

    @Test
    void anonymous_post_does_not_issue_a_token() throws Exception {
        mockMvc.perform(post("/api/realtime/token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void user_with_csrf_receives_json_token() throws Exception {
        mockMvc.perform(post("/api/realtime/token").with(user(principal(7L, Role.USER))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void admin_with_csrf_receives_admin_claim() throws Exception {
        String body = mockMvc.perform(post("/api/realtime/token").with(user(principal(8L, Role.ADMIN))).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String token = org.springframework.boot.json.JsonParserFactory.getJsonParser().parseMap(body).get("token").toString();
        var claims = SignedJWT.parse(token).getJWTClaimsSet();
        assertEquals("8", claims.getSubject());
        assertEquals("ADMIN", claims.getStringClaim("role"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void user_without_csrf_is_forbidden() throws Exception {
        mockMvc.perform(post("/api/realtime/token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unavailable_key_returns_json_503() throws Exception {
        ReflectionTestUtils.setField(controller, "tokenService", new RealtimeTokenService(
                new RealtimeJwtProperties("oms", "realtime-service", "", Duration.ofMinutes(5))));

        mockMvc.perform(post("/api/realtime/token").with(user(principal(7L, Role.USER))).with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(503));
    }

    private UserPrincipal principal(long id, Role role) {
        return new UserPrincipal(id, "user@example.com", "User", "010-0000-0000", "password", role);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        RealtimeJwtProperties realtimeJwtProperties() {
            return new RealtimeJwtProperties("oms", "realtime-service", privateKeyPem(), Duration.ofMinutes(5));
        }

        private String privateKeyPem() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair keyPair = generator.generateKeyPair();
                return "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded())
                        + "\n-----END PRIVATE KEY-----";
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
