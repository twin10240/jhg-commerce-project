package com.jhg.hgpage.realtime.outbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class NotificationOutboxTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T06:30:00Z");

    @Test
    void 대기이벤트는_선점후_발행완료된다() {
        NotificationOutbox outbox = pendingOutbox();

        outbox.claim(CREATED_AT.plusSeconds(1));
        outbox.markPublished(CREATED_AT.plusSeconds(2));

        assertThat(outbox.getStatus()).isEqualTo(NotificationOutboxStatus.PUBLISHED);
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getProcessingAt()).isNull();
        assertThat(outbox.getPublishedAt()).isEqualTo(CREATED_AT.plusSeconds(2));
    }

    @Test
    void 처리실패는_재시도대기상태로_되돌아간다() {
        NotificationOutbox outbox = pendingOutbox();
        outbox.claim(CREATED_AT.plusSeconds(1));

        outbox.retry(CREATED_AT.plusSeconds(61), "TIMEOUT");

        assertThat(outbox.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getProcessingAt()).isNull();
        assertThat(outbox.getNextAttemptAt()).isEqualTo(CREATED_AT.plusSeconds(61));
        assertThat(outbox.getLastErrorCode()).isEqualTo("TIMEOUT");
    }

    @Test
    void 오래된_처리중_이벤트는_다시_대기상태가_된다() {
        NotificationOutbox outbox = pendingOutbox();
        outbox.claim(CREATED_AT.plusSeconds(1));

        outbox.recoverStale(CREATED_AT.plusSeconds(62));

        assertThat(outbox.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getProcessingAt()).isNull();
        assertThat(outbox.getNextAttemptAt()).isEqualTo(CREATED_AT.plusSeconds(62));
    }

    @Test
    void 처리실패는_최종실패로_전환되고_더이상_발행할수없다() {
        NotificationOutbox outbox = pendingOutbox();
        outbox.claim(CREATED_AT.plusSeconds(1));

        outbox.markFailed("INVALID_CONTRACT");

        assertThat(outbox.getStatus()).isEqualTo(NotificationOutboxStatus.FAILED);
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getLastErrorCode()).isEqualTo("INVALID_CONTRACT");
        assertThatIllegalStateException().isThrownBy(() -> outbox.claim(CREATED_AT.plusSeconds(2)));
    }

    @Test
    void 허용되지않은_상태전이는_거부된다() {
        NotificationOutbox outbox = pendingOutbox();

        assertThatIllegalStateException().isThrownBy(() -> outbox.markPublished(CREATED_AT.plusSeconds(1)));
        assertThatIllegalStateException().isThrownBy(() -> outbox.retry(CREATED_AT.plusSeconds(1), "TIMEOUT"));
        assertThatIllegalStateException().isThrownBy(() -> outbox.recoverStale(CREATED_AT.plusSeconds(1)));
    }

    private NotificationOutbox pendingOutbox() {
        UUID id = UUID.fromString("a7df3d28-9eaa-4058-b3ae-52a2e31a35ed");
        UUID eventId = UUID.fromString("e133d8bf-9993-4d59-8b7a-c80fd2d2d37b");
        return NotificationOutbox.create(id, eventId, NotificationEventType.DELIVERY_COMPLETED,
                7L, "ORDER", "12", "{}", CREATED_AT);
    }
}
