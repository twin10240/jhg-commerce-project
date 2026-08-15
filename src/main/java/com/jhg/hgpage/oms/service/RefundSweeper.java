package com.jhg.hgpage.oms.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class RefundSweeper {

    private final RefundService refundService;
    private final RefundProcessor processor;

    @Scheduled(fixedDelayString = "${refunds.sweep-delay:5s}",
            initialDelayString = "${refunds.sweep-delay:5s}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        refundService.recoverStaleRefunds(now.minusMinutes(5), now);
        refundService.findDueRefundIds(now).forEach(processor::process);
    }
}
