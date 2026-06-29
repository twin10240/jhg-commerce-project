package com.jhg.hgpage.wms.adapter;

import com.jhg.hgpage.contract.InventoryQueryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * InventoryQueryPort REST 어댑터 (채널1: OMS→WMS 가용수량 조회).
 * @Primary로 등록돼 ProductService의 InventoryQueryPort 주입을 가로챈다.
 * InventoryService(OMS wms 패키지)는 S2까지 병존.
 */
@Primary
@Component
public class WmsInventoryQueryAdapter implements InventoryQueryPort {

    private final RestClient restClient;

    public WmsInventoryQueryAdapter(RestClient.Builder builder,
                                    @Value("${wms.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public Map<Long, Integer> availableByProductIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();
        String ids = productIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        Map<Long, Integer> result = restClient.get()
                .uri("/api/inventory/availability?productIds=" + ids)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return result != null ? result : Map.of();
    }
}
