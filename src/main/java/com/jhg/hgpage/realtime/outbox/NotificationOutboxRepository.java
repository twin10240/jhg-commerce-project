package com.jhg.hgpage.realtime.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, UUID> {

    Optional<NotificationOutbox> findByEventId(UUID eventId);
}
