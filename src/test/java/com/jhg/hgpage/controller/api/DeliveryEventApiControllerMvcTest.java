package com.jhg.hgpage.controller.api;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.oms.service.OrderService;
import com.jhg.hgpage.oms.web.api.DeliveryEventApiController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeliveryEventApiController.class)
@Import(SecurityConfig.class)
class DeliveryEventApiControllerMvcTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean OrderService orderService;

    private MockHttpServletRequestBuilder callback(String body) {
        return post("/api/delivery-events")
                .with(httpBasic("wms", "wms"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    void 배송완료_콜백을_주문_서비스에_위임한다() throws Exception {
        mockMvc.perform(callback("{\"orderId\":40,\"deliveredAt\":\"2026-08-27T06:30:00.123456Z\"}"))
                .andExpect(status().isOk());

        verify(orderService).markDelivered(40L, Instant.parse("2026-08-27T06:30:00.123456Z"));
    }

    @Test
    void orderId가_없으면_400이다() throws Exception {
        mockMvc.perform(callback("{\"orderId\":null,\"deliveredAt\":\"2026-08-27T06:30:00Z\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orderService);
    }

    @Test
    void 배송완료_시각이_없으면_400이다() throws Exception {
        mockMvc.perform(callback("{\"orderId\":40}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orderService);
    }

    @Test
    void 출고되지_않은_주문이면_409다() throws Exception {
        doThrow(new IllegalStateException("출고 완료 상태에서만 배송 완료할 수 있습니다."))
                .when(orderService).markDelivered(40L, Instant.parse("2026-08-27T06:30:00Z"));

        mockMvc.perform(callback("{\"orderId\":40,\"deliveredAt\":\"2026-08-27T06:30:00Z\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void 인증_없이는_401이다() throws Exception {
        mockMvc.perform(post("/api/delivery-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":40}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(orderService);
    }
}
