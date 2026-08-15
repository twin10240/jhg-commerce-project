package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.service.RefundProcessor;
import com.jhg.hgpage.oms.service.RefundService;
import com.jhg.hgpage.oms.service.RefundSweeper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundSweeperTest {

    @Mock RefundService refundService;
    @Mock RefundProcessor processor;

    @Test
    void stale_선점을_복구한뒤_도래한_환불을_처리한다() {
        when(refundService.findDueRefundIds(any())).thenReturn(List.of(10L, 20L));
        RefundSweeper sweeper = new RefundSweeper(refundService, processor);

        sweeper.sweep();

        InOrder calls = inOrder(refundService, processor);
        ArgumentCaptor<LocalDateTime> staleBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        calls.verify(refundService).recoverStaleRefunds(staleBefore.capture(), now.capture());
        calls.verify(refundService).findDueRefundIds(now.getValue());
        calls.verify(processor).process(10L);
        calls.verify(processor).process(20L);
        assertThat(staleBefore.getValue()).isEqualTo(now.getValue().minusMinutes(5));
    }
}
