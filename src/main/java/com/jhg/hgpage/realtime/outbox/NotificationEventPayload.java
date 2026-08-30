package com.jhg.hgpage.realtime.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record NotificationEventPayload(
        int schemaVersion, UUID eventId, NotificationEventType type, Instant occurredAt,
        Long recipientId, Aggregate aggregate, Map<String, Object> data) {

    public NotificationEventPayload {
        eventId = Objects.requireNonNull(eventId);
        type = Objects.requireNonNull(type);
        occurredAt = Objects.requireNonNull(occurredAt);
        recipientId = Objects.requireNonNull(recipientId);
        aggregate = Objects.requireNonNull(aggregate);
        data = Map.copyOf(data);
    }

    public record Aggregate(String type, String id) {
        public Aggregate {
            type = Objects.requireNonNull(type);
            id = Objects.requireNonNull(id);
        }
    }
}
