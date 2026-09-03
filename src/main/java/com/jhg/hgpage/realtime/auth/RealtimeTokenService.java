package com.jhg.hgpage.realtime.auth;

import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class RealtimeTokenService {
    private final RealtimeJwtProperties properties;
    private volatile JwtEncoder encoder;

    public RealtimeTokenService(RealtimeJwtProperties properties) {
        this.properties = properties;
    }

    public TokenResponse issue(UserPrincipal principal, Instant now) {
        Objects.requireNonNull(principal, "principal must not be null");
        Instant expiresAt = now.plus(properties.ttl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(principal.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("role", principal.getRole().name())
                .build();
        try {
            return new TokenResponse(encoder().encode(JwtEncoderParameters.from(claims)).getTokenValue(), expiresAt);
        } catch (TokenUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TokenUnavailableException(e);
        }
    }

    private JwtEncoder encoder() {
        if (encoder == null) {
            synchronized (this) {
                if (encoder == null) {
                    encoder = createEncoder();
                }
            }
        }
        return encoder;
    }

    private JwtEncoder createEncoder() {
        try {
            if (!StringUtils.hasText(properties.privateKey())) {
                throw new IllegalArgumentException("Realtime JWT private key is not configured");
            }
            RSAPrivateKey privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(properties.privateKey()
                            .replaceAll("-----BEGIN (?:RSA )?PRIVATE KEY-----|-----END (?:RSA )?PRIVATE KEY-----|\\s", ""))));
            if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
                throw new IllegalArgumentException("Realtime JWT private key must be RSA CRT");
            }
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
            RSAKey jwk = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
            return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(jwk)));
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            throw new TokenUnavailableException(e);
        }
    }

    public record TokenResponse(String token, Instant expiresAt) {
    }

    public static class TokenUnavailableException extends RuntimeException {
        TokenUnavailableException(Throwable cause) {
            super("Realtime token service is unavailable", cause);
        }
    }
}
