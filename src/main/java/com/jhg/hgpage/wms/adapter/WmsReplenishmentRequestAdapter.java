package com.jhg.hgpage.wms.adapter;

import com.jhg.hgpage.wms.dto.ReplenishmentRequestDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

@Component
public class WmsReplenishmentRequestAdapter {

    private final RestClient restClient;

    public WmsReplenishmentRequestAdapter(RestClient.Builder builder,
                                          @Value("${wms.base-url}") String baseUrl,
                                          @Value("${wms.basic.user:wms}") String basicUser,
                                          @Value("${wms.basic.password:wms}") String basicPassword) {
        this.restClient = builder.baseUrl(baseUrl)
                .defaultHeaders(headers -> headers.setBasicAuth(basicUser, basicPassword))
                .build();
    }

    public record RequestLine(Long productId, int requestedQty) {}
    private record CreateRequest(UUID requestKey, String reason, List<RequestLine> items) {}

    public ReplenishmentRequestDto create(UUID key, List<RequestLine> items, String reason) {
        var request = new CreateRequest(key, reason, items == null ? null : List.copyOf(items));
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return restClient.post()
                        .uri("/api/replenishment-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(ReplenishmentRequestDto.class);
            } catch (HttpClientErrorException exception) {
                if (exception.getStatusCode() == HttpStatus.BAD_REQUEST)
                    throw new IllegalArgumentException("WMS replenishment request rejected", exception);
                if (exception.getStatusCode() == HttpStatus.CONFLICT)
                    throw new IllegalStateException("WMS replenishment request conflicts", exception);
                throw exception;
            } catch (ResourceAccessException | HttpServerErrorException exception) {
                if (attempt == 1)
                    throw new IllegalStateException("WMS replenishment request failed", exception);
            }
        }
        throw new IllegalStateException("WMS replenishment request failed");
    }

    public List<ReplenishmentRequestDto> findAll() {
        try {
            List<ReplenishmentRequestDto> result = restClient.get()
                    .uri("/api/replenishment-requests")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return result != null ? result : List.of();
        } catch (ResourceAccessException exception) {
            return List.of();
        }
    }
}
