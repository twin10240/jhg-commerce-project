package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.contract.InventoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CancellationProcessor {

    private final OrderCancellationService cancellationService;
    private final InventoryPort inventoryPort;
    private final BackorderAllocator backorderAllocator;

    public void process(Long orderId) {
        cancellationService.claim(orderId).ifPresent(claim -> {
            try {
                if (claim.releaseRequired()) {
                    inventoryPort.releaseAll(orderId, claim.quantities());
                }
                if (cancellationService.complete(orderId, claim.attemptNumber()) && claim.releaseRequired()) {
                    backorderAllocator.allocate(claim.quantities().keySet());
                }
            } catch (RuntimeException exception) {
                cancellationService.retry(orderId, claim.attemptNumber());
            }
        });
    }
}
