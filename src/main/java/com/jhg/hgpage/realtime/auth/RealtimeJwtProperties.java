package com.jhg.hgpage.realtime.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("realtime.jwt")
public record RealtimeJwtProperties(String issuer, String audience, String privateKey, Duration ttl) {
}
