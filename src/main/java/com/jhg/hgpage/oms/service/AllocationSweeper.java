package com.jhg.hgpage.oms.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class AllocationSweeper {

    private final OrderAllocationService orderAllocationService;
    private final AllocationProcessor processor;

    @Scheduled(fixedDelayString = "${allocations.sweep-delay:5s}",
            initialDelayString = "${allocations.sweep-delay:5s}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        orderAllocationService.recoverStaleAllocations(now.minusMinutes(5), now);
        orderAllocationService.findDueAllocationOrderIds(now).forEach(processor::process);
    }
}
