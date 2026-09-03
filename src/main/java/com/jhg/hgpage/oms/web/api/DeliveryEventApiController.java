package com.jhg.hgpage.oms.web.api;

import com.jhg.hgpage.oms.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * WMS 배송완료 콜백. 창고가 기록한 사실을 받아 Delivery를 DELIVERED로 올린다.
 * 통지는 WMS 쪽에서 best-effort라 재발송될 수 있어 멱등이다(이미 DELIVERED면 200).
 * <p>WMS가 기록한 deliveredAt을 함께 저장해 반품 기간·배송 SLA의 기준 시각으로 쓸 수 있게 한다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DeliveryEventApiController {

    private final OrderService orderService;

    @PostMapping("/api/delivery-events")
    public ResponseEntity<Void> receive(@RequestBody(required = false) DeliveryEvent event) {
        if (event == null || event.requestKey() == null || event.deliveredAt() == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            orderService.markDelivered(event.requestKey(), event.deliveredAt());
            return ResponseEntity.ok().build();
        } catch (IllegalStateException e) {
            // 출고되지 않은 주문에 배송완료가 온 경우 — 두 시스템의 상태가 어긋났다는 신호라 삼키지 않는다.
            log.warn("WMS 배송완료 콜백 거부: requestKey={}, orderId={}, {}",
                    event.requestKey(), event.orderId(), e.getMessage());
            return ResponseEntity.status(409).build();
        }
    }

    public record DeliveryEvent(UUID requestKey, Long orderId, Instant deliveredAt) {}
}
