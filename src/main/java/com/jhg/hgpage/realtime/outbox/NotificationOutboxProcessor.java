package com.jhg.hgpage.realtime.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "realtime.outbox.enabled", havingValue = "true")
public class NotificationOutboxProcessor {

    private final NotificationOutboxService service;
    private final OutboxDeliveryClient deliveryClient;

    public NotificationOutboxProcessor(NotificationOutboxService service, OutboxDeliveryClient deliveryClient) {
        this.service = service;
        this.deliveryClient = deliveryClient;
    }

    public void process(UUID id) {
        service.claim(id, Instant.now()).ifPresent(command -> {
            DeliveryResult result = deliveryClient.deliver(command.eventId(), command.payload(), Instant.now());
            service.applyResult(id, command.attempt(), result, Instant.now());
        });
    }
}
