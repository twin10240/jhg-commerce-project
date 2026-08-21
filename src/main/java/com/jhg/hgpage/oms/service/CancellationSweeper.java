package com.jhg.hgpage.oms.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class CancellationSweeper {

    private final OrderCancellationService cancellationService;
    private final CancellationProcessor processor;

    @Scheduled(fixedDelayString = "${cancellations.sweep-delay:5s}",
            initialDelayString = "${cancellations.sweep-delay:5s}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        cancellationService.recoverStaleCancellations(now.minusMinutes(5), now);
        cancellationService.findDueCancellationOrderIds().forEach(processor::process);
    }
}
