package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.service.AllocationProcessor;
import com.jhg.hgpage.oms.service.AllocationSweeper;
import com.jhg.hgpage.oms.service.OrderAllocationService;
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
class AllocationSweeperTest {

    @Mock OrderAllocationService allocationService;
    @Mock AllocationProcessor processor;

    @Test
    void 오래된_선점을_복구한뒤_FIFO_순서로_도래한_주문을_처리한다() {
        when(allocationService.findDueAllocationOrderIds(any())).thenReturn(List.of(10L, 20L));
        AllocationSweeper sweeper = new AllocationSweeper(allocationService, processor);

        sweeper.sweep();

        InOrder calls = inOrder(allocationService, processor);
        ArgumentCaptor<LocalDateTime> staleBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        calls.verify(allocationService).recoverStaleAllocations(staleBefore.capture(), now.capture());
        calls.verify(allocationService).findDueAllocationOrderIds(now.getValue());
        calls.verify(processor).process(10L);
        calls.verify(processor).process(20L);
        assertThat(staleBefore.getValue()).isEqualTo(now.getValue().minusMinutes(5));
    }
}
