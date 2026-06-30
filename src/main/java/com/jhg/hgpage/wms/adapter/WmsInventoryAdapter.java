package com.jhg.hgpage.wms.adapter;

import com.jhg.hgpage.contract.InventoryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * InventoryPort REST 어댑터 (OMS→WMS 예약/출고/해제).
 * @Primary로 인프로세스 InventoryService의 InventoryPort 구현을 교체한다.
 */
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
}
