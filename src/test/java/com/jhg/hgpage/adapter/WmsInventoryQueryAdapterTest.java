package com.jhg.hgpage.adapter;

import com.jhg.hgpage.wms.adapter.WmsInventoryQueryAdapter;
import com.jhg.hgpage.wms.dto.InventoryRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RestClientTest(WmsInventoryQueryAdapter.class)
@TestPropertySource(properties = "wms.base-url=http://wms-test")
class WmsInventoryQueryAdapterTest {

    @Autowired MockRestServiceServer server;
    @Autowired WmsInventoryQueryAdapter adapter;

    @Test
    void WMS_채널1로_productId별_가용수량을_조회한다() {
        server.expect(requestToUriTemplate("http://wms-test/api/inventory/availability?productIds={ids}", "1,2"))
              .andRespond(withSuccess("{\"1\":10,\"2\":5}", MediaType.APPLICATION_JSON));

        Map<Long, Integer> result = adapter.availableByProductIds(List.of(1L, 2L));

        assertThat(result).containsEntry(1L, 10).containsEntry(2L, 5);
        server.verify();
    }

    @Test
    void 빈_목록은_WMS_미호출_후_빈_맵을_반환한다() {
        // server에 아무 expect도 없음 → verify()가 호출이 없었음을 검증
        Map<Long, Integer> result = adapter.availableByProductIds(List.of());

        assertThat(result).isEmpty();
        server.verify(); // WMS로 HTTP 요청이 나가지 않았음을 보장
    }

    @Test
    void allRows_WMS에서_전체_재고_목록을_조회한다() {
        server.expect(requestTo("http://wms-test/api/inventory/rows"))
              .andRespond(withSuccess("[{\"productId\":1,\"productName\":\"상품 1\",\"onHandQty\":10}]", MediaType.APPLICATION_JSON));

        List<InventoryRow> result = adapter.allRows();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).productId()).isEqualTo(1L);
        assertThat(result.get(0).productName()).isEqualTo("상품 1");
        assertThat(result.get(0).onHandQty()).isEqualTo(10);
        server.verify();
    }
}
