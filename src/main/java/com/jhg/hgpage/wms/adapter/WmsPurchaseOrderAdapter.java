package com.jhg.hgpage.wms.adapter;

import com.jhg.hgpage.wms.dto.PurchaseOrderDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class WmsPurchaseOrderAdapter {

    private final RestClient restClient;

    public WmsPurchaseOrderAdapter(RestClient.Builder builder,
                                   @Value("${wms.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public record PurchaseOrderLine(Long productId, int quantity) {}
    private record CreateRequest(List<PurchaseOrderLine> lines, String memo) {}

    public List<PurchaseOrderDto> findAllWithItems() {
        List<PurchaseOrderDto> result = restClient.get()
                .uri("/api/purchase-orders")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return result != null ? result : List.of();
    }

    public Long create(List<PurchaseOrderLine> lines, String memo) {
        PurchaseOrderDto dto = restClient.post()
                .uri("/api/purchase-orders")
                .body(new CreateRequest(lines, memo))
                .retrieve()
                .body(PurchaseOrderDto.class);
        return dto != null ? dto.id() : null;
    }

    public Long receive(Long poId) {
        try {
            restClient.post()
                    .uri("/api/purchase-orders/receive?poId={id}", poId)
                    .retrieve()
                    .toBodilessEntity();
            return poId;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND)
                throw new IllegalArgumentException("발주가 없습니다: id=" + poId);
            throw new IllegalStateException("입고 처리 실패: " + e.getMessage());
        }
    }
}
