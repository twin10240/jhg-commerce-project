package com.jhg.hgpage.adapter;

import com.jhg.hgpage.contract.ReturnPort;
import com.jhg.hgpage.contract.ReturnPort.CreateItem;
import com.jhg.hgpage.contract.ReturnPort.CreateRequest;
import com.jhg.hgpage.contract.ReturnPort.ReturnResult;
import com.jhg.hgpage.wms.adapter.WmsReturnAdapter;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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

@RestClientTest(WmsReturnAdapter.class)
@TestPropertySource(properties = "wms.base-url=http://wms-test")
class WmsReturnAdapterTest {

    private static final UUID REQUEST_KEY = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired MockRestServiceServer server;
    @Autowired WmsReturnAdapter adapter;

    @Test
    void create_posts_wms_return_shape_and_maps_201_response() {
        server.expect(requestTo("http://wms-test/api/returns"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic d21zOndtcw=="))
                .andExpect(content().json(requestJson()))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(responseJson()));

        ReturnResult result = adapter.create(request());

        assertThat(result.rmaId()).isEqualTo(30L);
        assertThat(result.requestKey()).isEqualTo(REQUEST_KEY);
        assertThat(result.orderId()).isEqualTo(100L);
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.orderItemId()).isEqualTo(501L);
            assertThat(item.productId()).isEqualTo(1L);
            assertThat(item.requestedQuantity()).isEqualTo(2);
            assertThat(item.acceptedQuantity()).isEqualTo(1);
            assertThat(item.disposition()).isEqualTo("RESTOCKED");
        });
        server.verify();
    }

    @Test
    void create_maps_200_response() {
        server.expect(requestTo("http://wms-test/api/returns"))
                .andRespond(withSuccess(responseJson(), MediaType.APPLICATION_JSON));

        assertThat(adapter.create(request()).rmaId()).isEqualTo(30L);
        server.verify();
    }

    @Test
    void create_maps_400_to_permanent_bad_request() {
        server.expect(requestTo("http://wms-test/api/returns")).andRespond(withBadRequest());

        assertThatThrownBy(() -> adapter.create(request()))
                .isInstanceOfSatisfying(ReturnPort.PermanentReturnRejection.class,
                        error -> assertThat(error.code()).isEqualTo("BAD_REQUEST"));
        server.verify();
    }

    @Test
    void create_maps_409_to_permanent_conflict() {
        server.expect(requestTo("http://wms-test/api/returns")).andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> adapter.create(request()))
                .isInstanceOfSatisfying(ReturnPort.PermanentReturnRejection.class,
                        error -> assertThat(error.code()).isEqualTo("CONFLICT"));
        server.verify();
    }

    @Test
    void create_maps_401_to_authentication_failure() {
        server.expect(requestTo("http://wms-test/api/returns")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> adapter.create(request()))
                .isInstanceOf(ReturnPort.ReturnAuthenticationFailure.class);
        server.verify();
    }

    @Test
    void create_maps_403_to_authentication_failure() {
        server.expect(requestTo("http://wms-test/api/returns")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> adapter.create(request()))
                .isInstanceOf(ReturnPort.ReturnAuthenticationFailure.class);
        server.verify();
    }

    @Test
    void create_maps_server_failure_to_transient_failure() {
        server.expect(requestTo("http://wms-test/api/returns")).andRespond(withServerError());

        assertThatThrownBy(() -> adapter.create(request()))
                .isInstanceOf(ReturnPort.TransientReturnFailure.class);
        server.verify();
    }

    @Test
    void create_maps_transport_failure_to_transient_failure() {
        server.expect(requestTo("http://wms-test/api/returns"))
                .andRespond(withException(new SocketTimeoutException("timeout")));

        assertThatThrownBy(() -> adapter.create(request()))
                .isInstanceOf(ReturnPort.TransientReturnFailure.class);
        server.verify();
    }

    @Test
    void create_maps_empty_success_response_to_safe_transient_failure() {
        server.expect(requestTo("http://wms-test/api/returns")).andRespond(withSuccess());

        assertInvalidSuccessResponse(() -> adapter.create(request()));
        server.verify();
    }

    @Test
    void create_maps_partial_success_response_to_safe_transient_failure() {
        server.expect(requestTo("http://wms-test/api/returns"))
                .andRespond(withSuccess("{\"rmaId\":30}", MediaType.APPLICATION_JSON));

        assertInvalidSuccessResponse(() -> adapter.create(request()));
        server.verify();
    }

    @Test
    void create_maps_malformed_success_response_to_safe_transient_failure() {
        server.expect(requestTo("http://wms-test/api/returns"))
                .andRespond(withSuccess("{\"rmaId\":", MediaType.APPLICATION_JSON));

        assertInvalidSuccessResponse(() -> adapter.create(request()));
        server.verify();
    }

    @Test
    void create_maps_unsupported_success_response_to_safe_transient_failure() {
        server.expect(requestTo("http://wms-test/api/returns"))
                .andRespond(withSuccess("remote-secret", MediaType.TEXT_PLAIN));

        assertInvalidSuccessResponse(() -> adapter.create(request()));
        server.verify();
    }

    @Test
    void create_maps_empty_items_to_safe_transient_failure() {
        server.expect(requestTo("http://wms-test/api/returns"))
                .andRespond(withSuccess(responseJson("[]"), MediaType.APPLICATION_JSON));

        assertInvalidSuccessResponse(() -> adapter.create(request()));
        server.verify();
    }

    @Test
    void create_maps_non_positive_nested_ids_to_safe_transient_failure() {
        server.expect(requestTo("http://wms-test/api/returns"))
                .andRespond(withSuccess(responseJson("""
                        [{"orderItemId":0,"productId":-1,"requestedQuantity":2,"acceptedQuantity":1,"disposition":"RESTOCKED"}]
                        """), MediaType.APPLICATION_JSON));

        assertInvalidSuccessResponse(() -> adapter.create(request()));
        server.verify();
    }

    @Test
    void create_maps_invalid_order_item_id_to_safe_transient_failure() {
        server.expect(requestTo("http://wms-test/api/returns"))
                .andRespond(withSuccess(responseJson("""
                        [{"orderItemId":0,"productId":1,"requestedQuantity":2,"acceptedQuantity":1,"disposition":"RESTOCKED"}]
                        """), MediaType.APPLICATION_JSON));

        assertInvalidSuccessResponse(() -> adapter.create(request()));
        server.verify();
    }

    @Test
    void create_maps_invalid_product_id_to_safe_transient_failure() {
        server.expect(requestTo("http://wms-test/api/returns"))
                .andRespond(withSuccess(responseJson("""
                        [{"orderItemId":501,"productId":0,"requestedQuantity":2,"acceptedQuantity":1,"disposition":"RESTOCKED"}]
                        """), MediaType.APPLICATION_JSON));

        assertInvalidSuccessResponse(() -> adapter.create(request()));
        server.verify();
    }

    @Test
    void find_gets_wms_return_shape() {
        server.expect(requestTo("http://wms-test/api/returns/30"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseJson(), MediaType.APPLICATION_JSON));

        ReturnResult result = adapter.find(30L);

        assertThat(result.rmaId()).isEqualTo(30L);
        assertThat(result.items()).singleElement()
                .extracting(item -> item.disposition())
                .isEqualTo("RESTOCKED");
        server.verify();
    }

    @Test
    void find_maps_404_to_remote_not_found() {
        server.expect(requestTo("http://wms-test/api/returns/30")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> adapter.find(30L))
                .isInstanceOfSatisfying(ReturnPort.RemoteReturnNotFound.class,
                        error -> assertThat(error).hasMessage("RMA not found: 30"));
        server.verify();
    }

    @Test
    void find_maps_empty_success_response_to_safe_transient_failure() {
        server.expect(requestTo("http://wms-test/api/returns/30")).andRespond(withSuccess());

        assertInvalidSuccessResponse(() -> adapter.find(30L));
        server.verify();
    }

    @Test
    void find_maps_partial_success_response_to_safe_transient_failure() {
        server.expect(requestTo("http://wms-test/api/returns/30"))
                .andRespond(withSuccess("{\"rmaId\":30}", MediaType.APPLICATION_JSON));

        assertInvalidSuccessResponse(() -> adapter.find(30L));
        server.verify();
    }

    @Test
    void find_maps_malformed_success_response_to_safe_transient_failure() {
        server.expect(requestTo("http://wms-test/api/returns/30"))
                .andRespond(withSuccess("{\"rmaId\":", MediaType.APPLICATION_JSON));

        assertInvalidSuccessResponse(() -> adapter.find(30L));
        server.verify();
    }

    @Test
    void find_maps_unsupported_success_response_to_safe_transient_failure() {
        server.expect(requestTo("http://wms-test/api/returns/30"))
                .andRespond(withSuccess("remote-secret", MediaType.TEXT_PLAIN));

        assertInvalidSuccessResponse(() -> adapter.find(30L));
        server.verify();
    }

    @Test
    void find_maps_empty_items_to_safe_transient_failure() {
        server.expect(requestTo("http://wms-test/api/returns/30"))
                .andRespond(withSuccess(responseJson("[]"), MediaType.APPLICATION_JSON));

        assertInvalidSuccessResponse(() -> adapter.find(30L));
        server.verify();
    }

    @Test
    void find_maps_non_positive_nested_ids_to_safe_transient_failure() {
        server.expect(requestTo("http://wms-test/api/returns/30"))
                .andRespond(withSuccess(responseJson("""
                        [{"orderItemId":0,"productId":-1,"requestedQuantity":2,"acceptedQuantity":1,"disposition":"RESTOCKED"}]
                        """), MediaType.APPLICATION_JSON));

        assertInvalidSuccessResponse(() -> adapter.find(30L));
        server.verify();
    }

    @Test
    void find_maps_invalid_order_item_id_to_safe_transient_failure() {
        server.expect(requestTo("http://wms-test/api/returns/30"))
                .andRespond(withSuccess(responseJson("""
                        [{"orderItemId":0,"productId":1,"requestedQuantity":2,"acceptedQuantity":1,"disposition":"RESTOCKED"}]
                        """), MediaType.APPLICATION_JSON));

        assertInvalidSuccessResponse(() -> adapter.find(30L));
        server.verify();
    }

    @Test
    void find_maps_invalid_product_id_to_safe_transient_failure() {
        server.expect(requestTo("http://wms-test/api/returns/30"))
                .andRespond(withSuccess(responseJson("""
                        [{"orderItemId":501,"productId":0,"requestedQuantity":2,"acceptedQuantity":1,"disposition":"RESTOCKED"}]
                        """), MediaType.APPLICATION_JSON));

        assertInvalidSuccessResponse(() -> adapter.find(30L));
        server.verify();
    }

    private CreateRequest request() {
        return new CreateRequest(REQUEST_KEY, 100L, "불량", List.of(new CreateItem(501L, 1L, 2)));
    }

    private String requestJson() {
        return """
                {"requestKey":"00000000-0000-0000-0000-000000000001","orderId":100,"reason":"불량","items":[{"orderItemId":501,"productId":1,"quantity":2}]}
                """;
    }

    private String responseJson() {
        return responseJson("""
                [{"orderItemId":501,"productId":1,"requestedQuantity":2,"acceptedQuantity":1,"disposition":"RESTOCKED"}]
                """);
    }

    private String responseJson(String items) {
        return """
                {"rmaId":30,"requestKey":"00000000-0000-0000-0000-000000000001","orderId":100,"status":"COMPLETED","items":%s}
                """.formatted(items);
    }

    private void assertInvalidSuccessResponse(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ReturnPort.TransientReturnFailure.class, error -> {
                    assertThat(error.getCause()).isExactlyInstanceOf(IllegalStateException.class);
                    assertThat(error.getCause()).hasMessage("Invalid WMS return response");
                });
    }
}
