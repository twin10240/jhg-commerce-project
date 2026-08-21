package com.jhg.hgpage.oms.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class RefundSweeper {

    private final RefundService refundService;
    private final RefundProcessor processor;
    private final Duration processingTimeout;

    public RefundSweeper(RefundService refundService, RefundProcessor processor) {
        this(refundService, processor, Duration.ofMinutes(5));
    }

    @Autowired
    public RefundSweeper(RefundService refundService, RefundProcessor processor,
                         @Value("${refunds.processing-timeout:5m}") Duration processingTimeout) {
        this.refundService = refundService;
        this.processor = processor;
        this.processingTimeout = processingTimeout;
    }

    @Scheduled(fixedDelayString = "${refunds.sweep-delay:5s}",
            initialDelayString = "${refunds.sweep-delay:5s}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        refundService.recoverStaleRefunds(now.minus(processingTimeout), now);
        refundService.findDueRefundIds(now).forEach(processor::process);
    }
}
