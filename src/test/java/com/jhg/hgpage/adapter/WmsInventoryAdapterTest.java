package com.jhg.hgpage.adapter;

import com.jhg.hgpage.wms.adapter.WmsInventoryAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RestClientTest(WmsInventoryAdapter.class)
@TestPropertySource(properties = "wms.base-url=http://wms-test")
class WmsInventoryAdapterTest {

    private static final UUID REQUEST_KEY = UUID.fromString("3f2a9c14-8b7e-4d21-9f60-0c5a1e7b4d33");

    @Autowired MockRestServiceServer server;
    @Autowired WmsInventoryAdapter adapter;

    @Test
    void reserve_WMS에_POST_요청을_보내고_결과를_반환한다() {
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().json("""
                      {"requestKey":"3f2a9c14-8b7e-4d21-9f60-0c5a1e7b4d33","orderId":1,"items":{"1":3}}
                      """))
              .andRespond(withSuccess("true", MediaType.APPLICATION_JSON));

        boolean result = adapter.reserveAll(REQUEST_KEY, 1L, Map.of(1L, 3));

        assertThat(result).isTrue();
        server.verify();
    }

    @Test
    void ship_WMS_응답의_소수점_시각과_송장을_반환한다() {
        server.expect(requestTo("http://wms-test/api/inventory/ship"))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().json("""
                      {"requestKey":"3f2a9c14-8b7e-4d21-9f60-0c5a1e7b4d33","items":{"1":3}}
                      """))
              .andRespond(withSuccess("""
                      {"requestKey":"3f2a9c14-8b7e-4d21-9f60-0c5a1e7b4d33","orderId":1,
                       "carrierCode":"MOCK","carrierName":"테스트택배",
                       "trackingNumber":"MOCK-1-20260827063000-3f2a9c14","issuedAt":"2026-08-27T06:30:00.123456Z"}
                      """, MediaType.APPLICATION_JSON));

        var result = adapter.shipAll(REQUEST_KEY, Map.of(1L, 3));

        assertThat(result.requestKey()).isEqualTo(REQUEST_KEY);
        assertThat(result.orderId()).isEqualTo(1L);
        assertThat(result.trackingNumber()).isEqualTo("MOCK-1-20260827063000-3f2a9c14");
        assertThat(result.issuedAt()).isEqualTo(Instant.parse("2026-08-27T06:30:00.123456Z"));
        server.verify();
    }

    @Test
    void ship_409_평문은_JSON으로_파싱하지_않고_상태코드_예외로_반환한다() {
        server.expect(requestTo("http://wms-test/api/inventory/ship"))
              .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                      .withStatus(org.springframework.http.HttpStatus.CONFLICT)
                      .body("주문에 대한 예약이 없어 출고할 수 없습니다.")
                      .contentType(MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> adapter.shipAll(REQUEST_KEY, Map.of(1L, 999)))
                .isInstanceOf(HttpClientErrorException.Conflict.class);
        server.verify();
    }

    @Test
    void ship_응답은_requestKey로_검증한다() {
        server.expect(requestTo("http://wms-test/api/inventory/ship"))
              .andRespond(withSuccess("""
                      {"requestKey":"11111111-1111-1111-1111-111111111111","orderId":1,"carrierCode":"MOCK","carrierName":"테스트택배",
                       "trackingNumber":"MOCK-1","issuedAt":"2026-08-27T06:30:00.1Z"}
                      """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.shipAll(REQUEST_KEY, Map.of(1L, 3)))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("응답이 잘못되었습니다");
        server.verify();
    }

    @Test
    void release_WMS에_POST_요청을_보낸다() {
        server.expect(requestTo("http://wms-test/api/inventory/release"))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().json("""
                      {"requestKey":"3f2a9c14-8b7e-4d21-9f60-0c5a1e7b4d33","items":{"1":3}}
                      """))
              .andRespond(withSuccess());

        adapter.releaseAll(REQUEST_KEY, Map.of(1L, 3));
        server.verify();
    }

    // ── S4: 재시도/강등 ──────────────────────────────────────────

    @Test
    void reserve_통신실패면_1회_재시도하고_성공하면_true() {
        // 첫 요청이 실제로는 WMS에 닿았어도 orderId 멱등이라 재시도는 같은 결과로 수렴한다.
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andRespond(withException(new SocketTimeoutException("read timeout")));
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andRespond(withSuccess("true", MediaType.APPLICATION_JSON));

        boolean result = adapter.reserveAll(REQUEST_KEY, 1L, Map.of(1L, 3));

        assertThat(result).isTrue();
        server.verify(); // 정확히 2회 호출
    }

    @Test
    void reserve_통신재시도까지_실패하면_예외를_유지한다() {
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andRespond(withException(new ConnectException("refused")));
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andRespond(withException(new ConnectException("refused")));

        assertThatThrownBy(() -> adapter.reserveAll(REQUEST_KEY, 1L, Map.of(1L, 3)))
                .isInstanceOf(ResourceAccessException.class);
        server.verify();
    }

    @Test
    void reserve_명시적_false만_재고부족으로_반환한다() {
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andRespond(withSuccess("false", MediaType.APPLICATION_JSON));

        assertThat(adapter.reserveAll(REQUEST_KEY, 1L, Map.of(1L, 3))).isFalse();
        server.verify();
    }

    @Test
    void reserve_빈_성공응답은_재고부족으로_해석하지_않는다() {
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.reserveAll(REQUEST_KEY, 1L, Map.of(1L, 3)))
                .isInstanceOf(RestClientException.class);
        server.verify();
    }

    // WMS 5xx(동시 예약 낙관적 락 → 500)도 통신실패와 동일하게 재시도→백오더로 흡수한다(#22).
    @Test
    void reserve_WMS가_500이면_1회_재시도하고_성공하면_true() {
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andRespond(withServerError());
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andRespond(withSuccess("true", MediaType.APPLICATION_JSON));

        boolean result = adapter.reserveAll(REQUEST_KEY, 1L, Map.of(1L, 3));

        assertThat(result).isTrue();
        server.verify();
    }

    @Test
    void reserve_WMS가_500을_반복하면_예외를_유지한다() {
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andRespond(withServerError());
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andRespond(withServerError());

        assertThatThrownBy(() -> adapter.reserveAll(REQUEST_KEY, 1L, Map.of(1L, 3)))
                .isInstanceOf(HttpServerErrorException.class);
        server.verify();
    }

    // 4xx는 재시도/강등 대상이 아니라 그대로 표면화되어야 한다(잘못된 요청을 백오더로 삼키지 않음).
    @Test
    void reserve_WMS가_400이면_삼키지_않고_예외를_던진다() {
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andRespond(withBadRequest());

        assertThatThrownBy(() -> adapter.reserveAll(REQUEST_KEY, 1L, Map.of(1L, 3)))
                .isInstanceOf(HttpClientErrorException.class);
        server.verify();
    }
}
