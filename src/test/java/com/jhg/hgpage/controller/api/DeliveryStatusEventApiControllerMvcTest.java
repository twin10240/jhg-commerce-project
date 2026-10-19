package com.jhg.hgpage.controller.api;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.oms.service.OrderService;
import com.jhg.hgpage.oms.web.api.DeliveryStatusEventApiController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeliveryStatusEventApiController.class)
@Import(SecurityConfig.class)
class DeliveryStatusEventApiControllerMvcTest {
    private static final UUID KEY = UUID.fromString("3f2a9c14-8b7e-4d21-9f60-0c5a1e7b4d33");
    private static final Instant AT = Instant.parse("2026-10-14T00:00:00Z");
    private static final String PAYLOAD = """
            {"requestKey":"3f2a9c14-8b7e-4d21-9f60-0c5a1e7b4d33","orderId":40,
             "status":"DELIVERY_FAILED","deliveryVersion":2,"occurredAt":"2026-10-14T00:00:00Z",
             "failureCode":"ADDRESS_ISSUE"}
            """;
    @Autowired MockMvc mvc;
    @MockitoBean OrderService service;

    private MockHttpServletRequestBuilder callback(String body) {
        return post("/api/delivery-status-events").with(httpBasic("wms", "wms"))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void 내부_Basic_인증으로_배송실패를_받는다() throws Exception {
        mvc.perform(callback(PAYLOAD)).andExpect(status().isOk());
        verify(service).applyDeliveryStatus(KEY, "DELIVERY_FAILED", 2, AT, "ADDRESS_ISSUE");
    }

    @Test
    void 인증과_본문_검증을_통과하지_못하면_업무를_수행하지_않는다() throws Exception {
        mvc.perform(post("/api/delivery-status-events").contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isUnauthorized());
        mvc.perform(callback("null")).andExpect(status().isBadRequest());
        mvc.perform(callback(PAYLOAD.replace("ADDRESS_ISSUE", "FREE_TEXT"))).andExpect(status().isBadRequest());
        mvc.perform(callback(PAYLOAD.replace("\"deliveryVersion\":2", "\"deliveryVersion\":0"))).andExpect(status().isBadRequest());
        mvc.perform(callback(PAYLOAD.replace("\"deliveryVersion\":2,", ""))).andExpect(status().isBadRequest());
        mvc.perform(callback(PAYLOAD.replace("DELIVERY_FAILED", "READY"))).andExpect(status().isBadRequest());
        mvc.perform(callback(PAYLOAD.replace("\"orderId\":40", "\"orderId\":null"))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void 배송상태_충돌은_409다() throws Exception {
        doThrow(new IllegalStateException("conflict")).when(service).applyDeliveryStatus(KEY, "DELIVERY_FAILED", 2, AT, "ADDRESS_ISSUE");
        mvc.perform(callback(PAYLOAD)).andExpect(status().isConflict());
    }

    @Test
    void 고객_웹세션은_내부_배송상태를_변경할_수_없다() throws Exception {
        mvc.perform(post("/api/delivery-status-events")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("customer").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
