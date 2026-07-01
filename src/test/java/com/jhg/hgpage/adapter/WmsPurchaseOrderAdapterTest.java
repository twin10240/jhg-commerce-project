package com.jhg.hgpage.adapter;

import com.jhg.hgpage.wms.adapter.WmsPurchaseOrderAdapter;
import com.jhg.hgpage.wms.adapter.WmsPurchaseOrderAdapter.PurchaseOrderLine;
import com.jhg.hgpage.wms.dto.PurchaseOrderDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@RestClientTest(WmsPurchaseOrderAdapter.class)
@TestPropertySource(properties = "wms.base-url=http://wms-test")
class WmsPurchaseOrderAdapterTest {

    @Autowired MockRestServiceServer server;
    @Autowired WmsPurchaseOrderAdapter adapter;

    @Test
    void findAllWithItems_WMS에_GET_요청을_보내고_목록을_반환한다() {
        server.expect(requestTo("http://wms-test/api/purchase-orders"))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withSuccess(
                  "[{\"id\":1,\"status\":\"ORDERED\",\"memo\":\"긴급\",\"createdAt\":\"2026-07-01T10:00:00\",\"receivedAt\":null,\"items\":[{\"id\":1,\"productId\":1,\"quantity\":10}]}]",
                  MediaType.APPLICATION_JSON));

        List<PurchaseOrderDto> result = adapter.findAllWithItems();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("ORDERED");
        assertThat(result.get(0).items().get(0).productId()).isEqualTo(1L);
        server.verify();
    }

    @Test
    void create_WMS에_POST_요청을_보내고_발주번호를_반환한다() {
        server.expect(requestTo("http://wms-test/api/purchase-orders"))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().json("{\"lines\":[{\"productId\":1,\"quantity\":10}],\"memo\":\"긴급\"}"))
              .andRespond(withSuccess(
                  "{\"id\":7,\"status\":\"ORDERED\",\"memo\":\"긴급\",\"createdAt\":\"2026-07-01T10:00:00\",\"receivedAt\":null,\"items\":[]}",
                  MediaType.APPLICATION_JSON));

        Long poId = adapter.create(List.of(new PurchaseOrderLine(1L, 10)), "긴급");

        assertThat(poId).isEqualTo(7L);
        server.verify();
    }

    @Test
    void receive_WMS에_POST_요청을_보낸다() {
        server.expect(requestTo("http://wms-test/api/purchase-orders/receive?poId=7"))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess());

        adapter.receive(7L);
        server.verify();
    }

    @Test
    void receive_404면_IllegalArgumentException을_던진다() {
        server.expect(requestTo("http://wms-test/api/purchase-orders/receive?poId=99"))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> adapter.receive(99L))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void receive_409면_IllegalStateException을_던진다() {
        server.expect(requestTo("http://wms-test/api/purchase-orders/receive?poId=7"))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT));

        assertThatThrownBy(() -> adapter.receive(7L))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }
}
