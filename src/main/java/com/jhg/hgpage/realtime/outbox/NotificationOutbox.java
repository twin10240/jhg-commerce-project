package com.jhg.hgpage.realtime.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Getter
@Table(name = "notification_outbox")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationOutbox {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private NotificationEventType eventType;

    @Column(name = "aggregate_type", nullable = false, length = 30)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "processing_at")
    private Instant processingAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private Long version;

    public static NotificationOutbox create(UUID id, UUID eventId, NotificationEventType eventType,
                                             Long recipientId, String aggregateType, String aggregateId,
                                             String payload, Instant createdAt) {
        NotificationOutbox outbox = new NotificationOutbox();
        outbox.id = Objects.requireNonNull(id);
        outbox.eventId = Objects.requireNonNull(eventId);
        outbox.eventType = Objects.requireNonNull(eventType);
        outbox.recipientId = Objects.requireNonNull(recipientId);
        outbox.aggregateType = Objects.requireNonNull(aggregateType);
        outbox.aggregateId = Objects.requireNonNull(aggregateId);
        outbox.payload = Objects.requireNonNull(payload);
        outbox.createdAt = Objects.requireNonNull(createdAt);
        outbox.nextAttemptAt = createdAt;
        outbox.status = NotificationOutboxStatus.PENDING;
        return outbox;
    }

    public void claim(Instant now) {
        requireStatus(NotificationOutboxStatus.PENDING);
        processingAt = Objects.requireNonNull(now);
        status = NotificationOutboxStatus.PROCESSING;
        attemptCount++;
    }

    public void markPublished(Instant now) {
        requireStatus(NotificationOutboxStatus.PROCESSING);
        publishedAt = Objects.requireNonNull(now);
        processingAt = null;
        status = NotificationOutboxStatus.PUBLISHED;
    }

    public void retry(Instant nextAttemptAt, String errorCode) {
        requireStatus(NotificationOutboxStatus.PROCESSING);
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt);
        lastErrorCode = Objects.requireNonNull(errorCode);
        processingAt = null;
        status = NotificationOutboxStatus.PENDING;
    }

    public void recoverStale(Instant now) {
        requireStatus(NotificationOutboxStatus.PROCESSING);
        nextAttemptAt = Objects.requireNonNull(now);
        processingAt = null;
        status = NotificationOutboxStatus.PENDING;
    }

    public void markFailed(String errorCode) {
        requireStatus(NotificationOutboxStatus.PROCESSING);
        lastErrorCode = Objects.requireNonNull(errorCode);
        processingAt = null;
        status = NotificationOutboxStatus.FAILED;
    }

    private void requireStatus(NotificationOutboxStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Invalid notification outbox transition from " + status);
        }
    }
}
