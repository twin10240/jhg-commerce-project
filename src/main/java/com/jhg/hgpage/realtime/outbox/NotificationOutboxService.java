package com.jhg.hgpage.realtime.outbox;

import com.jhg.hgpage.oms.service.RetrySchedule;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class NotificationOutboxService {

    private static final int MAX_BATCH_SIZE = 50;

    private final NotificationOutboxRepository repository;
    private final RetrySchedule retrySchedule;

    public NotificationOutboxService(NotificationOutboxRepository repository, RetrySchedule retrySchedule) {
        this.repository = repository;
        this.retrySchedule = retrySchedule;
    }

    @Transactional(readOnly = true)
    public List<UUID> findDueIds(Instant now, int limit) {
        return repository.findDueIds(now, PageRequest.of(0, Math.min(limit, MAX_BATCH_SIZE)));
    }

    @Transactional
    public Optional<DeliveryCommand> claim(UUID id, Instant now) {
        NotificationOutbox outbox = repository.findByIdForUpdate(id).orElse(null);
        if (outbox == null || outbox.getStatus() != NotificationOutboxStatus.PENDING
                || outbox.getNextAttemptAt().isAfter(now)) {
            return Optional.empty();
        }
        outbox.claim(now);
        return Optional.of(new DeliveryCommand(outbox.getEventId(), outbox.getPayload(), outbox.getAttemptCount()));
    }

    @Transactional
    public void applyResult(UUID id, int attempt, DeliveryResult result, Instant now) {
        NotificationOutbox outbox = repository.findByIdForUpdate(id).orElse(null);
        if (outbox == null || outbox.getStatus() != NotificationOutboxStatus.PROCESSING
                || outbox.getAttemptCount() != attempt) {
            return;
        }
        if (result.outcome() == DeliveryResult.Outcome.SUCCESS) {
            outbox.markPublished(now);
        } else if (result.outcome() == DeliveryResult.Outcome.PERMANENT_FAILURE) {
            outbox.markFailed(result.errorCode());
        } else {
            retrySchedule.nextAttemptAt(attempt, LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                    .ifPresentOrElse(next -> outbox.retry(next.toInstant(ZoneOffset.UTC), result.errorCode()),
                            () -> outbox.markFailed(result.errorCode()));
        }
    }

    @Transactional
    public void recoverStale(Instant staleBefore, Instant now) {
        for (UUID id : repository.findStaleIds(staleBefore, PageRequest.of(0, MAX_BATCH_SIZE))) {
            NotificationOutbox outbox = repository.findByIdForUpdate(id).orElse(null);
            if (outbox != null && outbox.getStatus() == NotificationOutboxStatus.PROCESSING
                    && !outbox.getProcessingAt().isAfter(staleBefore)) {
                outbox.recoverStale(now);
            }
        }
    }

    @Transactional
    public void deletePublishedBefore(Instant publishedBefore) {
        repository.deleteByStatusAndPublishedAtBefore(NotificationOutboxStatus.PUBLISHED, publishedBefore);
    }

    @Transactional(readOnly = true)
    public List<FailedEvent> findFailed() {
        return repository.findByStatusOrderByCreatedAtDesc(NotificationOutboxStatus.FAILED).stream()
                .map(outbox -> new FailedEvent(outbox.getId(), outbox.getEventId(), outbox.getEventType().name(),
                        outbox.getAggregateType(), outbox.getAggregateId(), outbox.getAttemptCount(),
                        outbox.getLastErrorCode(), outbox.getCreatedAt()))
                .toList();
    }

    @Transactional
    public boolean requeueFailed(UUID id, Instant now) {
        NotificationOutbox outbox = repository.findByIdForUpdate(id).orElse(null);
        if (outbox == null || outbox.getStatus() != NotificationOutboxStatus.FAILED) {
            return false;
        }
        outbox.requeue(now);
        return true;
    }

    public record DeliveryCommand(UUID eventId, String payload, int attempt) { }

    public record FailedEvent(UUID id, UUID eventId, String eventType, String aggregateType, String aggregateId,
                              int attemptCount, String lastErrorCode, Instant createdAt) { }
}
