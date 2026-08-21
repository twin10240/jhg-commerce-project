package com.jhg.hgpage.oms.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class AllocationSweeper {

    private final OrderAllocationService orderAllocationService;
    private final AllocationProcessor processor;
    private final Duration processingTimeout;

    public AllocationSweeper(OrderAllocationService orderAllocationService, AllocationProcessor processor) {
        this(orderAllocationService, processor, Duration.ofMinutes(5));
    }

    @Autowired
    public AllocationSweeper(OrderAllocationService orderAllocationService, AllocationProcessor processor,
                             @Value("${allocation.processing-timeout:5m}") Duration processingTimeout) {
        this.orderAllocationService = orderAllocationService;
        this.processor = processor;
        this.processingTimeout = processingTimeout;
    }

    @Scheduled(fixedDelayString = "${allocation.sweep-delay:5s}",
            initialDelayString = "${allocation.sweep-delay:5s}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        orderAllocationService.recoverStaleAllocations(now.minus(processingTimeout), now);
        orderAllocationService.findDueAllocationOrderIds(now).forEach(processor::process);
    }
}
