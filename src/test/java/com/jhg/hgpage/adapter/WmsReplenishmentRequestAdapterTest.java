package com.jhg.hgpage.adapter;

import com.jhg.hgpage.wms.adapter.WmsReplenishmentRequestAdapter;
import com.jhg.hgpage.wms.adapter.WmsReplenishmentRequestAdapter.RequestLine;
import com.jhg.hgpage.wms.dto.ReplenishmentRequestDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@RestClientTest(WmsReplenishmentRequestAdapter.class)
@TestPropertySource(properties = "wms.base-url=http://wms-test")
class WmsReplenishmentRequestAdapterTest {

    @Autowired MockRestServiceServer server;
    @Autowired WmsReplenishmentRequestAdapter adapter;

    @Test
    void createPostsRequestWithBasicAuth() {
        UUID key = UUID.randomUUID();
        server.expect(requestTo("http://wms-test/api/replenishment-requests"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic d21zOndtcw=="))
                .andExpect(content().json(requestJson(key)))
                .andRespond(withSuccess(responseJson(key), MediaType.APPLICATION_JSON));

        ReplenishmentRequestDto result = adapter.create(key,
                List.of(new RequestLine(1L, 3), new RequestLine(2L, 5)), "low stock");

        assertThat(result.id()).isEqualTo(11L);
        assertThat(result.items()).extracting(ReplenishmentRequestDto.ItemDto::requestedQty)
                .containsExactly(3, 5);
        server.verify();
    }

    @Test
    void findAllMapsResponse() {
        UUID key = UUID.randomUUID();
        server.expect(requestTo("http://wms-test/api/replenishment-requests"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic d21zOndtcw=="))
                .andRespond(withSuccess("[" + responseJson(key) + "]", MediaType.APPLICATION_JSON));

        List<ReplenishmentRequestDto> result = adapter.findAll();

        assertThat(result).singleElement().satisfies(request -> {
            assertThat(request.requestKey()).isEqualTo(key);
            assertThat(request.status()).isEqualTo("REQUESTED");
            assertThat(request.requestedAt()).isNotNull();
            assertThat(request.items()).extracting(ReplenishmentRequestDto.ItemDto::productId)
                    .containsExactly(1L, 2L);
        });
        server.verify();
    }

    @Test
    void createRetriesTransportFailureWithSameBody() {
        UUID key = UUID.randomUUID();
        server.expect(requestTo("http://wms-test/api/replenishment-requests"))
                .andExpect(content().json(requestJson(key)))
                .andRespond(withException(new SocketTimeoutException("timeout")));
        server.expect(requestTo("http://wms-test/api/replenishment-requests"))
                .andExpect(content().json(requestJson(key)))
                .andRespond(withSuccess(responseJson(key), MediaType.APPLICATION_JSON));

        assertThat(adapter.create(key, List.of(new RequestLine(1L, 3), new RequestLine(2L, 5)), "low stock").id())
                .isEqualTo(11L);
        server.verify();
    }

    @Test
    void createMapsBadRequest() {
        server.expect(requestTo("http://wms-test/api/replenishment-requests"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> adapter.create(UUID.randomUUID(), List.of(), "reason"))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void createMapsConflict() {
        server.expect(requestTo("http://wms-test/api/replenishment-requests"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> adapter.create(UUID.randomUUID(), List.of(new RequestLine(1L, 1)), "reason"))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void createMapsRepeatedServerFailure() {
        server.expect(requestTo("http://wms-test/api/replenishment-requests")).andRespond(withServerError());
        server.expect(requestTo("http://wms-test/api/replenishment-requests")).andRespond(withServerError());

        assertThatThrownBy(() -> adapter.create(UUID.randomUUID(), List.of(new RequestLine(1L, 1)), "reason"))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void findAllFallsBackToEmptyList() {
        server.expect(requestTo("http://wms-test/api/replenishment-requests"))
                .andRespond(withException(new SocketTimeoutException("timeout")));

        assertThat(adapter.findAll()).isEmpty();
        server.verify();
    }

    private String requestJson(UUID key) {
        return """
                {"requestKey":"%s","reason":"low stock","items":[
                  {"productId":1,"requestedQty":3},{"productId":2,"requestedQty":5}
                ]}
                """.formatted(key);
    }

    private String responseJson(UUID key) {
        return """
                {"id":11,"requestKey":"%s","reason":"low stock","status":"REQUESTED",
                 "requestedAt":"2026-07-16T10:00:00","decidedAt":null,"fulfilledAt":null,
                 "wmsMemo":null,"purchaseOrderId":null,"items":[
                   {"productId":1,"requestedQty":3},{"productId":2,"requestedQty":5}
                 ]}
                """.formatted(key);
    }
}
