package com.jhg.hgpage.oms.web.api;

import com.jhg.hgpage.contract.StockReplenishedHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * WMS→OMS 재고 보충 콜백(채널3) 수신. WMS에서 재고가 늘면(입고·+조정) 호출되어
 * 백오더 승격을 트리거한다. 통지는 자연 멱등 — 중복 수신해도 승격할 게 없으면 no-op.
 */
@RestController
@RequiredArgsConstructor
public class ReplenishmentApiController {

    private final StockReplenishedHandler stockReplenishedHandler;

    public record ReplenishmentRequest(List<Long> productIds) {}

    @PostMapping("/api/replenishments")
    public ResponseEntity<Void> replenished(@RequestBody ReplenishmentRequest request) {
        if (request.productIds() == null || request.productIds().isEmpty())
            return ResponseEntity.badRequest().build();
        stockReplenishedHandler.onReplenished(request.productIds());
        return ResponseEntity.ok().build();
    }
}
