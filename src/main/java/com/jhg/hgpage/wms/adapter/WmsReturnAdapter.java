package com.jhg.hgpage.wms.adapter;

import com.jhg.hgpage.contract.ReturnPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class WmsReturnAdapter implements ReturnPort {

    private final RestClient restClient;

    public WmsReturnAdapter(RestClient.Builder builder,
                            @Value("${wms.base-url}") String baseUrl,
                            @Value("${wms.basic.user:wms}") String basicUser,
                            @Value("${wms.basic.password:wms}") String basicPassword) {
        restClient = builder.baseUrl(baseUrl)
                .defaultHeaders(headers -> headers.setBasicAuth(basicUser, basicPassword))
                .build();
    }

    @Override
    public ReturnResult create(CreateRequest request) {
        try {
            return validResponse(restClient.post()
                    .uri("/api/returns")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (httpRequest, response) -> {
                        throw postFailure(response.getStatusCode());
                    })
                    .body(ReturnResult.class));
        } catch (ResourceAccessException exception) {
            throw new TransientReturnFailure(exception);
        } catch (RestClientException exception) {
            throw invalidResponse();
        }
    }

    @Override
    public ReturnResult find(Long rmaId) {
        try {
            return validResponse(restClient.get()
                    .uri("/api/returns/{rmaId}", rmaId)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (httpRequest, response) -> {
                        throw findFailure(response.getStatusCode(), rmaId);
                    })
                    .body(ReturnResult.class));
        } catch (ResourceAccessException exception) {
            throw new TransientReturnFailure(exception);
        } catch (RestClientException exception) {
            throw invalidResponse();
        }
    }

    private ReturnResult validResponse(ReturnResult result) {
        if (result == null || result.rmaId() == null || result.rmaId() <= 0
                || result.requestKey() == null || result.orderId() == null || result.orderId() <= 0
                || result.status() == null || result.status().isBlank() || result.items() == null || result.items().isEmpty()
                || result.items().stream().anyMatch(item -> item == null || item.orderItemId() == null || item.orderItemId() <= 0
                || item.productId() == null || item.productId() <= 0 || item.requestedQuantity() <= 0)) {
            throw invalidResponse();
        }
        return result;
    }

    private TransientReturnFailure invalidResponse() {
        return new TransientReturnFailure(new IllegalStateException("Invalid WMS return response"));
    }

    private RuntimeException postFailure(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> new PermanentReturnRejection("BAD_REQUEST");
            case 401, 403 -> new ReturnAuthenticationFailure();
            case 409 -> new PermanentReturnRejection("CONFLICT");
            default -> failure(status);
        };
    }

    private RuntimeException findFailure(HttpStatusCode status, Long rmaId) {
        if (status.value() == 404) return new RemoteReturnNotFound(rmaId);
        if (status.value() == 401 || status.value() == 403) return new ReturnAuthenticationFailure();
        return failure(status);
    }

    private RuntimeException failure(HttpStatusCode status) {
        if (status.is5xxServerError())
            return new TransientReturnFailure(new IllegalStateException("WMS server error"));
        return new PermanentReturnRejection("HTTP_" + status.value());
    }
}
