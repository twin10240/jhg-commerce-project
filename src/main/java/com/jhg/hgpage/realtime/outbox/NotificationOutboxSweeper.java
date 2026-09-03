package com.jhg.hgpage.realtime.outbox;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
@ConditionalOnProperty(name = "realtime.outbox.enabled", havingValue = "true")
public class NotificationOutboxSweeper {

    private final NotificationOutboxService service;
    private final NotificationOutboxProcessor processor;
    private final Duration processingTimeout;

    @Autowired
    public NotificationOutboxSweeper(NotificationOutboxService service, NotificationOutboxProcessor processor,
                                     @Value("${realtime.outbox.processing-timeout:1m}") Duration processingTimeout) {
        this.service = service;
        this.processor = processor;
        this.processingTimeout = processingTimeout;
    }

    @Scheduled(fixedDelayString = "${realtime.outbox.sweep-delay:1s}")
    public void sweep() {
        Instant now = Instant.now();
        service.recoverStale(now.minus(processingTimeout), now);
        service.deletePublishedBefore(now.minus(Duration.ofDays(7)));
        service.findDueIds(now, 50).forEach(processor::process);
    }
}
