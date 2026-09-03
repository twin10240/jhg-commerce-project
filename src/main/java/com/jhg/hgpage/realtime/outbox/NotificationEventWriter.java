package com.jhg.hgpage.realtime.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationEventWriter {

    private final NotificationOutboxRepository repository;
    private final ObjectMapper objectMapper;

    public NotificationEventWriter(NotificationOutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UUID append(NotificationEventType type, Long recipientId, String aggregateType,
                       String aggregateId, Map<String, Object> data) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        NotificationEventPayload payload = new NotificationEventPayload(1, eventId, type, occurredAt,
                recipientId, new NotificationEventPayload.Aggregate(aggregateType, aggregateId), data);
        String serializedPayload = serialize(payload);

        repository.save(NotificationOutbox.create(UUID.randomUUID(), eventId, type, recipientId,
                aggregateType, aggregateId, serializedPayload, occurredAt));
        return eventId;
    }

    private String serialize(NotificationEventPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize notification event", e);
        }
    }
}
