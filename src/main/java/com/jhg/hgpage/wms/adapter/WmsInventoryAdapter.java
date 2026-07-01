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
            Boolean result = restClient.post()
                    .uri("/api/inventory/reserve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new WriteRequest(orderId, qtyByProductId))
                    .retrieve()
                    .body(Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (ResourceAccessException e) {
            // ponytail: WMS 다운 시 false → BACKORDERED 접수. 재고 확인 불가 = 가용분 없음으로 간주.
            log.warn("WMS 연결 실패 — reserveAll 실패, BACKORDERED로 접수: orderId={}", orderId);
            return false;
        }
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
