package com.jhg.hgpage.wms.adapter;

import com.jhg.hgpage.contract.InventoryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * InventoryPort REST 어댑터 (OMS→WMS 예약/출고/해제).
 * @Primary로 인프로세스 InventoryService의 InventoryPort 구현을 교체한다.
 */
@Slf4j
@Primary
@Component
public class WmsInventoryAdapter implements InventoryPort {

    private final RestClient restClient;

    public WmsInventoryAdapter(RestClient.Builder builder,
                               @Value("${wms.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    record WriteRequest(Long orderId, Map<Long, Integer> items) {}

    @Override
    public boolean reserveAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        try {
            return doReserve(orderId, qtyByProductId);
        } catch (ResourceAccessException first) {
            // 일시적 blip 구제 — orderId 멱등이라 첫 요청이 실제 예약됐어도 재시도가 같은 결과로 수렴(S4).
            log.warn("WMS 예약 통신 실패 — 1회 재시도: orderId={}", orderId);
            try {
                return doReserve(orderId, qtyByProductId);
            } catch (ResourceAccessException second) {
                // ponytail: WMS 다운 시 false → BACKORDERED 접수("예약 못 해본 백오더"). 회수는 보상 스윕.
                log.warn("WMS 예약 재시도 실패 — BACKORDERED로 접수: orderId={}", orderId);
                return false;
            }
        }
    }

    private boolean doReserve(Long orderId, Map<Long, Integer> qtyByProductId) {
        Boolean result = restClient.post()
                .uri("/api/inventory/reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new WriteRequest(orderId, qtyByProductId))
                .retrieve()
                .body(Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    @Override
    public void shipAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        restClient.post()
                .uri("/api/inventory/ship")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new WriteRequest(orderId, qtyByProductId))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void releaseAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        restClient.post()
                .uri("/api/inventory/release")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new WriteRequest(orderId, qtyByProductId))
                .retrieve()
                .toBodilessEntity();
    }

    /** 관리자 재고 조정. WMS 400은 IllegalArgumentException으로 변환한다. */
    public int adjust(Long productId, int delta, String reason) {
        try {
            record AdjustRequest(Long productId, int delta, String reason) {}
            Integer result = restClient.post()
                    .uri("/api/inventory/adjust")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AdjustRequest(productId, delta, reason))
                    .retrieve()
                    .body(Integer.class);
            return result != null ? result : 0;
        } catch (HttpClientErrorException e) {
            throw new IllegalArgumentException("WMS 재고 조정 실패: " + e.getStatusCode());
        }
    }
}
