package com.jhg.hgpage.realtime.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RealtimeAvailabilityWarning {

    private static final Logger log = LoggerFactory.getLogger(RealtimeAvailabilityWarning.class);

    public RealtimeAvailabilityWarning(
            @Value("${realtime.outbox.enabled:false}") boolean outboxEnabled,
            @Value("${realtime.chat.enabled:false}") boolean chatEnabled,
            @Value("${realtime.jwt.private-key:}") String privateKey) {
        if (!outboxEnabled && !chatEnabled && privateKey.isBlank()) {
            log.warn("실시간 알림 비활성 — outbox=off, chat=off, jwt-private-key 미설정");
        }
    }
}
