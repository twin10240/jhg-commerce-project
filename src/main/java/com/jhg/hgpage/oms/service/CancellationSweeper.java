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
        cancellationService.recoverStaleCancellations(LocalDateTime.now().minusMinutes(5));
        cancellationService.findDueCancellationOrderIds().forEach(processor::process);
    }
}
