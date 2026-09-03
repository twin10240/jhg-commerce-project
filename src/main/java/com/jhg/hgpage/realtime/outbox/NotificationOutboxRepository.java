package com.jhg.hgpage.realtime.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, UUID> {

    Optional<NotificationOutbox> findByEventId(UUID eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from NotificationOutbox o where o.id = :id")
    Optional<NotificationOutbox> findByIdForUpdate(UUID id);

    @Query("select o.id from NotificationOutbox o where o.status = com.jhg.hgpage.realtime.outbox.NotificationOutboxStatus.PENDING " +
            "and o.nextAttemptAt <= :now order by o.createdAt, o.id")
    List<UUID> findDueIds(Instant now, Pageable pageable);

    @Query("select o.id from NotificationOutbox o where o.status = com.jhg.hgpage.realtime.outbox.NotificationOutboxStatus.PROCESSING " +
            "and o.processingAt <= :staleBefore order by o.processingAt, o.id")
    List<UUID> findStaleIds(Instant staleBefore, Pageable pageable);

    long deleteByStatusAndPublishedAtBefore(NotificationOutboxStatus status, Instant publishedBefore);

    List<NotificationOutbox> findByStatusOrderByCreatedAtDesc(NotificationOutboxStatus status);
}
