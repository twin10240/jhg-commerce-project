package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.service.CancellationProcessor;
import com.jhg.hgpage.oms.service.CancellationSweeper;
import com.jhg.hgpage.oms.service.OrderCancellationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancellationSweeperTest {

    @Mock OrderCancellationService cancellationService;
    @Mock CancellationProcessor processor;

    @Test
    void stale_lease를_복구한뒤_처리가능한_취소를_실행한다() {
        when(cancellationService.findDueCancellationOrderIds()).thenReturn(List.of(10L, 20L));
        CancellationSweeper sweeper = new CancellationSweeper(cancellationService, processor);

        sweeper.sweep();

        InOrder calls = inOrder(cancellationService, processor);
        ArgumentCaptor<LocalDateTime> staleBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        calls.verify(cancellationService).recoverStaleCancellations(staleBefore.capture(), now.capture());
        calls.verify(cancellationService).findDueCancellationOrderIds();
        calls.verify(processor).process(10L);
        calls.verify(processor).process(20L);
        assertThat(staleBefore.getValue()).isBefore(LocalDateTime.now().minusMinutes(4));
        assertThat(now.getValue()).isAfter(staleBefore.getValue());
    }
}
