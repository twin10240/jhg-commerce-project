package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.contract.InventoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

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
                    inventoryPort.releaseAll(claim.requestKey(), claim.quantities());
                }
                if (cancellationService.complete(orderId, claim.attemptNumber()) && claim.releaseRequired()) {
                    backorderAllocator.allocate(claim.quantities().keySet());
                }
            } catch (HttpClientErrorException exception) {
                cancellationService.manualReview(orderId, claim.attemptNumber(),
                        "WMS_" + exception.getStatusCode().value());
            } catch (RuntimeException exception) {
                cancellationService.retryOrReview(orderId, claim.attemptNumber(), "WMS_UNAVAILABLE");
            }
        });
    }
}
