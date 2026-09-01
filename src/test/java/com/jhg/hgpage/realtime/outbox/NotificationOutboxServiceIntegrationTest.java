package com.jhg.hgpage.realtime.outbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:notification-outbox-claim-test;DB_CLOSE_DELAY=-1")
class NotificationOutboxServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-30T06:30:00Z");

    @Autowired NotificationOutboxRepository repository;
    @Autowired NotificationOutboxService service;

    private UUID id;

    @AfterEach
    void cleanUp() {
        if (id != null) {
            repository.deleteById(id);
        }
    }

    @Test
    void 동시선점과_오래된결과는_커밋된_행상태로_제어된다() throws Exception {
        id = UUID.randomUUID();
        repository.saveAndFlush(NotificationOutbox.create(id, UUID.randomUUID(), NotificationEventType.DELIVERY_COMPLETED,
                7L, "ORDER", "12", "{}", NOW.minusSeconds(120)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Optional<NotificationOutboxService.DeliveryCommand>>> claims = List.of(
                    executor.submit(() -> claimAfterStart(ready, start)),
                    executor.submit(() -> claimAfterStart(ready, start)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Optional<NotificationOutboxService.DeliveryCommand>> results = List.of(
                    claims.get(0).get(5, TimeUnit.SECONDS), claims.get(1).get(5, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Outbox claim workers did not terminate");
            }
        }

        NotificationOutbox firstClaim = repository.findById(id).orElseThrow();
        assertThat(firstClaim.getStatus()).isEqualTo(NotificationOutboxStatus.PROCESSING);
        assertThat(firstClaim.getAttemptCount()).isEqualTo(1);

        service.recoverStale(NOW.plusSeconds(1), NOW.plusSeconds(61));
        NotificationOutboxService.DeliveryCommand second = service.claim(id, NOW.plusSeconds(61)).orElseThrow();
        assertThat(second.attempt()).isEqualTo(2);

        service.applyResult(id, 1, DeliveryResult.success(), NOW.plusSeconds(62));
        NotificationOutbox afterOldResult = repository.findById(id).orElseThrow();
        assertThat(afterOldResult.getStatus()).isEqualTo(NotificationOutboxStatus.PROCESSING);
        assertThat(afterOldResult.getAttemptCount()).isEqualTo(2);

        service.applyResult(id, second.attempt(), DeliveryResult.success(), NOW.plusSeconds(63));
        NotificationOutbox published = repository.findById(id).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(NotificationOutboxStatus.PUBLISHED);
        assertThat(published.getAttemptCount()).isEqualTo(2);
    }

    private Optional<NotificationOutboxService.DeliveryCommand> claimAfterStart(CountDownLatch ready,
                                                                                  CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return service.claim(id, NOW);
    }
}
