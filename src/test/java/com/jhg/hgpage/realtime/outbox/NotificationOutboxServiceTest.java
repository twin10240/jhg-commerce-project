package com.jhg.hgpage.realtime.outbox;

import com.jhg.hgpage.oms.service.RetrySchedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationOutboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-30T06:30:00Z");

    @Mock NotificationOutboxRepository repository;

    @Test
    void 도래한_ID는_최대_50개만_조회한다() {
        UUID id = UUID.randomUUID();
        when(repository.findDueIds(any(), any())).thenReturn(List.of(id));
        NotificationOutboxService service = service();

        assertThat(service.findDueIds(NOW, 100)).containsExactly(id);

        ArgumentCaptor<org.springframework.data.domain.Pageable> page = ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(repository).findDueIds(eq(NOW), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void 재시도가능한_실패는_네번의_지정간격후_다섯번째에_실패한다() {
        NotificationOutbox outbox = pending();
        UUID id = outbox.getId();
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(outbox));
        NotificationOutboxService service = service();

        for (int attempt = 1; attempt <= 5; attempt++) {
            outbox.claim(NOW);
            service.applyResult(id, attempt, DeliveryResult.retryable("IO_FAILURE"), NOW);
            if (attempt < 5) {
                assertThat(outbox.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
                assertThat(outbox.getNextAttemptAt()).isEqualTo(NOW.plus(List.of(60L, 300L, 1800L, 7200L).get(attempt - 1), java.time.temporal.ChronoUnit.SECONDS));
            }
        }

        assertThat(outbox.getStatus()).isEqualTo(NotificationOutboxStatus.FAILED);
        assertThat(outbox.getLastErrorCode()).isEqualTo("IO_FAILURE");
    }

    @Test
    void 영구실패는_자동재시도없이_즉시_실패한다() {
        NotificationOutbox outbox = pending();
        outbox.claim(NOW);
        when(repository.findByIdForUpdate(outbox.getId())).thenReturn(Optional.of(outbox));

        service().applyResult(outbox.getId(), 1, DeliveryResult.permanent("HTTP_400"), NOW);

        assertThat(outbox.getStatus()).isEqualTo(NotificationOutboxStatus.FAILED);
        assertThat(outbox.getLastErrorCode()).isEqualTo("HTTP_400");
    }

    @Test
    void 오래된_처리중_행만_복구하고_발행완료_7일초과_행만_삭제한다() {
        NotificationOutbox outbox = pending();
        outbox.claim(NOW.minusSeconds(61));
        when(repository.findStaleIds(any(), any())).thenReturn(List.of(outbox.getId()));
        when(repository.findByIdForUpdate(outbox.getId())).thenReturn(Optional.of(outbox));
        NotificationOutboxService service = service();

        service.recoverStale(NOW.minusSeconds(60), NOW);
        service.deletePublishedBefore(NOW.minus(java.time.Duration.ofDays(7)));

        assertThat(outbox.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(outbox.getNextAttemptAt()).isEqualTo(NOW);
        verify(repository).deleteByStatusAndPublishedAtBefore(NotificationOutboxStatus.PUBLISHED,
                NOW.minus(java.time.Duration.ofDays(7)));
    }

    @Test
    void 다섯번째_처리제한시간_만료는_최종실패가되어_여섯번째_선점을_막는다() {
        NotificationOutbox outbox = pending();
        UUID id = outbox.getId();
        when(repository.findStaleIds(any(), any())).thenReturn(List.of(id));
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(outbox));
        NotificationOutboxService service = service();

        for (int attempt = 1; attempt <= 5; attempt++) {
            outbox.claim(NOW.minusSeconds(61));
            service.recoverStale(NOW.minusSeconds(60), NOW);
            if (attempt < 5) {
                assertThat(outbox.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
            }
        }

        assertThat(outbox.getStatus()).isEqualTo(NotificationOutboxStatus.FAILED);
        assertThat(outbox.getLastErrorCode()).isEqualTo("PROCESSING_TIMEOUT");
        assertThat(service.claim(id, NOW)).isEmpty();
    }

    @Test
    void 실패한_이벤트는_수동으로_새_대기시도로_되돌릴수있다() {
        NotificationOutbox outbox = pending();
        outbox.claim(NOW);
        outbox.markFailed("HTTP_422");
        when(repository.findByIdForUpdate(outbox.getId())).thenReturn(Optional.of(outbox));

        assertThat(service().requeueFailed(outbox.getId(), NOW)).isTrue();

        assertThat(outbox.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(outbox.getAttemptCount()).isZero();
        assertThat(outbox.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(outbox.getLastErrorCode()).isNull();
    }

    private NotificationOutboxService service() {
        return new NotificationOutboxService(repository, new RetrySchedule());
    }

    private NotificationOutbox pending() {
        return NotificationOutbox.create(UUID.randomUUID(), UUID.randomUUID(), NotificationEventType.DELIVERY_COMPLETED,
                7L, "ORDER", "12", "{}", NOW.minusSeconds(120));
    }
}
