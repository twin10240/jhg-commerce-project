package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.service.PaymentApprovalProcessor;
import com.jhg.hgpage.oms.service.PaymentApprovalSweeper;
import com.jhg.hgpage.oms.service.PaymentService;
import com.jhg.hgpage.oms.service.RetrySchedule;
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
class PaymentApprovalSweeperTest {

    @Mock PaymentService paymentService;
    @Mock PaymentApprovalProcessor processor;

    @Test
    void 오래된_처리중_시도를_복구한뒤_도래한_승인을_처리한다() {
        when(paymentService.findDueApprovalAttemptIds(any())).thenReturn(List.of(10L, 20L));
        PaymentApprovalSweeper sweeper = new PaymentApprovalSweeper(paymentService, processor);

        sweeper.sweep();

        InOrder inOrder = inOrder(paymentService, processor);
        ArgumentCaptor<LocalDateTime> staleBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        inOrder.verify(paymentService).recoverStaleApprovals(staleBefore.capture(), now.capture());
        inOrder.verify(paymentService).findDueApprovalAttemptIds(now.getValue());
        inOrder.verify(processor).process(10L);
        inOrder.verify(processor).process(20L);
        assertThat(staleBefore.getValue()).isEqualTo(now.getValue().minusMinutes(5));
    }

    @Test
    void 자동재시도는_네번까지만_지정된_간격으로_예약한다() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 15, 12, 0);
        RetrySchedule schedule = new RetrySchedule();

        assertThat(schedule.nextAttemptAt(1, now)).contains(now.plusMinutes(1));
        assertThat(schedule.nextAttemptAt(2, now)).contains(now.plusMinutes(5));
        assertThat(schedule.nextAttemptAt(3, now)).contains(now.plusMinutes(30));
        assertThat(schedule.nextAttemptAt(4, now)).contains(now.plusHours(2));
        assertThat(schedule.nextAttemptAt(5, now)).isEmpty();
    }
}
