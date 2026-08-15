package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.contract.InventoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

@Service
@RequiredArgsConstructor
public class AllocationProcessor {

    private final OrderAllocationService orderAllocationService;
    private final InventoryPort inventoryPort;

    public void process(Long orderId) {
        orderAllocationService.claim(orderId).ifPresent(command -> {
            try {
                boolean reserved = inventoryPort.reserveAll(orderId, command.quantities());
                orderAllocationService.complete(orderId, reserved);
            } catch (HttpClientErrorException exception) {
                orderAllocationService.manualReview(orderId, "WMS_" + exception.getStatusCode().value());
            } catch (RestClientException exception) {
                orderAllocationService.retryOrReview(orderId, "WMS_UNAVAILABLE");
            }
        });
    }
}
