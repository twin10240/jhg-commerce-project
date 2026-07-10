package com.jhg.hgpage.wms.adapter;

import com.jhg.hgpage.contract.InventoryQueryPort;
import com.jhg.hgpage.wms.dto.InventoryRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * InventoryQueryPort REST 어댑터 (채널1: OMS→WMS 가용수량 조회).
 * @Primary로 등록돼 ProductService의 InventoryQueryPort 주입을 가로챈다.
 * InventoryService(OMS wms 패키지)는 S2까지 병존.
 */
@Slf4j
@Primary
@Component
public class WmsInventoryQueryAdapter implements InventoryQueryPort {

    private final RestClient restClient;

    public WmsInventoryQueryAdapter(RestClient.Builder builder,
                                    @Value("${wms.base-url}") String baseUrl,
                                    @Value("${wms.basic.user:wms}") String basicUser,
                                    @Value("${wms.basic.password:wms}") String basicPassword) {
        this.restClient = builder.baseUrl(baseUrl)
                .defaultHeaders(h -> h.setBasicAuth(basicUser, basicPassword))
                .build();
    }

    @Override
    public Map<Long, Integer> availableByProductIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();
        String ids = productIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            Map<Long, Integer> result = restClient.get()
                    .uri("/api/inventory/availability?productIds=" + ids)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return result != null ? result : Map.of();
        } catch (ResourceAccessException e) {
            log.warn("WMS 연결 실패 — 가용수량 0으로 폴백: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 관리자 재고 화면용 전체 목록. */
    public List<InventoryRow> allRows() {
        try {
            List<InventoryRow> result = restClient.get()
                    .uri("/api/inventory/rows")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return result != null ? result : List.of();
        } catch (ResourceAccessException e) {
            log.warn("WMS 연결 실패 — 재고 목록 빈 목록으로 폴백: {}", e.getMessage());
            return List.of();
        }
    }
}
