package com.jhg.hgpage.oms.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class CancellationSweeper {

    private final OrderCancellationService cancellationService;
    private final CancellationProcessor processor;
    private final Duration processingTimeout;

    public CancellationSweeper(OrderCancellationService cancellationService, CancellationProcessor processor) {
        this(cancellationService, processor, Duration.ofMinutes(5));
    }

    @Autowired
    public CancellationSweeper(OrderCancellationService cancellationService, CancellationProcessor processor,
                               @Value("${cancellations.processing-timeout:5m}") Duration processingTimeout) {
        this.cancellationService = cancellationService;
        this.processor = processor;
        this.processingTimeout = processingTimeout;
    }

    @Scheduled(fixedDelayString = "${cancellations.sweep-delay:5s}",
            initialDelayString = "${cancellations.sweep-delay:5s}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        cancellationService.recoverStaleCancellations(now.minus(processingTimeout), now);
        cancellationService.findDueCancellationOrderIds().forEach(processor::process);
    }
}
