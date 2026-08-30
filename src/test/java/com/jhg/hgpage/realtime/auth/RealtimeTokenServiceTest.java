package com.jhg.hgpage.realtime.auth;

import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeTokenServiceTest {

    private final Instant now = Instant.parse("2026-08-30T00:00:00Z");

    @Test
    void authenticated_principal_receives_five_minute_connection_token() throws Exception {
        RealtimeTokenService service = new RealtimeTokenService(properties(privateKeyPem()));

        RealtimeTokenService.TokenResponse response = service.issue(principal(Role.USER), now);
        var claims = SignedJWT.parse(response.token()).getJWTClaimsSet();

        assertEquals("oms", claims.getIssuer());
        assertEquals("realtime-service", claims.getAudience().get(0));
        assertEquals("7", claims.getSubject());
        assertEquals("USER", claims.getStringClaim("role"));
        assertNotNull(claims.getJWTID());
        assertTrue(claims.getJWTID().matches("[0-9a-f-]{36}"));
        assertEquals(300, claims.getExpirationTime().toInstant().getEpochSecond() - claims.getIssueTime().toInstant().getEpochSecond());
        assertEquals(now.plusSeconds(300), response.expiresAt());
    }

    @Test
    void null_principal_is_rejected() {
        RealtimeTokenService service = new RealtimeTokenService(properties(""));

        assertThrows(NullPointerException.class, () -> service.issue(null, now));
    }

    @Test
    void unusable_rsa_key_is_reported_as_unavailable() throws Exception {
        RealtimeTokenService service = new RealtimeTokenService(properties(privateKeyPem(1024)));

        assertThrows(RealtimeTokenService.TokenUnavailableException.class, () -> service.issue(principal(Role.USER), now));
    }

    private UserPrincipal principal(Role role) {
        return new UserPrincipal(7L, "user@example.com", "User", "010-0000-0000", "password", role);
    }

    private RealtimeJwtProperties properties(String privateKey) {
        return new RealtimeJwtProperties("oms", "realtime-service", privateKey, Duration.ofMinutes(5));
    }

    private String privateKeyPem() throws Exception {
        return privateKeyPem(2048);
    }

    private String privateKeyPem(int keySize) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(keySize);
        KeyPair keyPair = generator.generateKeyPair();
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }
}
