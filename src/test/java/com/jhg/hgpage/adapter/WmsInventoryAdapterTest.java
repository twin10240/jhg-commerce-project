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

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RestClientTest(WmsInventoryAdapter.class)
@TestPropertySource(properties = "wms.base-url=http://wms-test")
class WmsInventoryAdapterTest {

    @Autowired MockRestServiceServer server;
    @Autowired WmsInventoryAdapter adapter;

    @Test
    void reserve_WMS에_POST_요청을_보내고_결과를_반환한다() {
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess("true", MediaType.APPLICATION_JSON));

        boolean result = adapter.reserveAll(1L, Map.of(1L, 3));

        assertThat(result).isTrue();
        server.verify();
    }

    @Test
    void ship_WMS에_POST_요청을_보낸다() {
        server.expect(requestTo("http://wms-test/api/inventory/ship"))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess());

        adapter.shipAll(1L, Map.of(1L, 3));
        server.verify();
    }

    @Test
    void release_WMS에_POST_요청을_보낸다() {
        server.expect(requestTo("http://wms-test/api/inventory/release"))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess());

        adapter.releaseAll(1L, Map.of(1L, 3));
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

        boolean result = adapter.reserveAll(1L, Map.of(1L, 3));

        assertThat(result).isTrue();
        server.verify(); // 정확히 2회 호출
    }

    @Test
    void reserve_재시도까지_실패하면_false로_강등한다() {
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andRespond(withException(new ConnectException("refused")));
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andRespond(withException(new ConnectException("refused")));

        boolean result = adapter.reserveAll(1L, Map.of(1L, 3));

        assertThat(result).isFalse();
        server.verify();
    }

    // WMS 5xx(동시 예약 낙관적 락 → 500)도 통신실패와 동일하게 재시도→백오더로 흡수한다(#22).
    @Test
    void reserve_WMS가_500이면_1회_재시도하고_성공하면_true() {
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andRespond(withServerError());
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andRespond(withSuccess("true", MediaType.APPLICATION_JSON));

        boolean result = adapter.reserveAll(1L, Map.of(1L, 3));

        assertThat(result).isTrue();
        server.verify();
    }

    @Test
    void reserve_WMS가_500을_반복하면_false로_강등한다() {
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andRespond(withServerError());
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andRespond(withServerError());

        boolean result = adapter.reserveAll(1L, Map.of(1L, 3));

        assertThat(result).isFalse();
        server.verify();
    }

    // 4xx는 재시도/강등 대상이 아니라 그대로 표면화되어야 한다(잘못된 요청을 백오더로 삼키지 않음).
    @Test
    void reserve_WMS가_400이면_삼키지_않고_예외를_던진다() {
        server.expect(requestTo("http://wms-test/api/inventory/reserve"))
              .andRespond(withBadRequest());

        assertThatThrownBy(() -> adapter.reserveAll(1L, Map.of(1L, 3)))
                .isInstanceOf(HttpClientErrorException.class);
        server.verify();
    }
}
