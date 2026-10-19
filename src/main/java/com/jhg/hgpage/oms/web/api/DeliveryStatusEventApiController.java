package com.jhg.hgpage.oms.web.api;

import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import com.jhg.hgpage.oms.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class DeliveryStatusEventApiController {
    private final OrderService orderService;

    @PostMapping("/api/delivery-status-events")
    public ResponseEntity<Void> receive(@RequestBody(required = false) DeliveryStatusEvent event) {
        if (event == null || event.requestKey() == null || event.orderId() == null || event.orderId() < 1
                || event.status() == null || event.deliveryVersion() == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            Delivery.validateStatusEvent(DeliveryStatus.valueOf(event.status()), event.deliveryVersion(), event.occurredAt(), event.failureCode());
            orderService.applyDeliveryStatus(event.requestKey(), event.status(), event.deliveryVersion(), event.occurredAt(), event.failureCode());
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(409).build();
        }
    }

    public record DeliveryStatusEvent(UUID requestKey, Long orderId, String status,
                                      Long deliveryVersion, Instant occurredAt, String failureCode) {}
}
