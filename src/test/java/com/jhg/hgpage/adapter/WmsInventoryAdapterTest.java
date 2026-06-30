package com.jhg.hgpage.adapter;

import com.jhg.hgpage.wms.adapter.WmsInventoryAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
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
}
