package com.jhg.hgpage.wms.adapter;

import com.jhg.hgpage.contract.InventoryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
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
                               @Value("${wms.base-url}") String baseUrl,
                               @Value("${wms.basic.user:wms}") String basicUser,
                               @Value("${wms.basic.password:wms}") String basicPassword) {
        this.restClient = builder.baseUrl(baseUrl)
                .defaultHeaders(h -> h.setBasicAuth(basicUser, basicPassword))
                .build();
    }

    record WriteRequest(Long orderId, Map<Long, Integer> items) {}

    @Override
    public boolean reserveAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        try {
            return doReserve(orderId, qtyByProductId);
        } catch (ResourceAccessException | HttpServerErrorException first) {
            // 통신 blip 또는 WMS 5xx(동시 예약 낙관적 락 충돌 #22)는 재시도 대상 — orderId 멱등이라
            // 첫 요청이 실제 예약됐어도 재시도가 같은 결과로 수렴(S4). 4xx는 안 잡아 그대로 표면화.
            log.warn("WMS 예약 실패 — 1회 재시도: orderId={}, cause={}", orderId, first.getClass().getSimpleName());
            try {
                return doReserve(orderId, qtyByProductId);
            } catch (ResourceAccessException | HttpServerErrorException second) {
                // ponytail: 재시도도 실패 → false → BACKORDERED 접수("예약 못 해본 백오더"). 회수는 보상 스윕(#22).
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

}
