package com.jhg.hgpage.realtime.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationOutboxSweeperTest {

    @Mock NotificationOutboxService service;
    @Mock NotificationOutboxProcessor processor;

    @Test
    void 복구_정리_도래행처리_순서로_한번의_UTC시각과_50개제한을_사용한다() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(service.findDueIds(any(), anyInt())).thenReturn(List.of(first, second));
        NotificationOutboxSweeper sweeper = new NotificationOutboxSweeper(service, processor, Duration.ofMinutes(1));

        sweeper.sweep();

        InOrder ordered = inOrder(service, processor);
        ArgumentCaptor<Instant> staleBefore = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        ordered.verify(service).recoverStale(staleBefore.capture(), now.capture());
        ordered.verify(service).deletePublishedBefore(now.getValue().minus(Duration.ofDays(7)));
        ordered.verify(service).findDueIds(now.getValue(), 50);
        ordered.verify(processor).process(first);
        ordered.verify(processor).process(second);
        assertThat(staleBefore.getValue()).isEqualTo(now.getValue().minus(Duration.ofMinutes(1)));
    }
}
