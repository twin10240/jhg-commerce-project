package com.jhg.hgpage.controller.api;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.contract.StockReplenishedHandler;
import com.jhg.hgpage.oms.web.api.ReplenishmentApiController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReplenishmentApiController.class)
@Import(SecurityConfig.class)
class ReplenishmentApiControllerMvcTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean StockReplenishedHandler stockReplenishedHandler;

    // 의도적으로 .with(user())·.with(csrf()) 없음 — WMS 서버 간 호출은 세션도 CSRF 토큰도 없다.
    // SecurityConfig의 permitAll + CSRF 예외가 빠지면 이 테스트가 401/403으로 잡는다.
    @Test
    void 인증과_CSRF_없이_콜백을_수신해_핸들러에_위임한다() throws Exception {
        mockMvc.perform(post("/api/replenishments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[1,2]}"))
                .andExpect(status().isOk());

        verify(stockReplenishedHandler).onReplenished(List.of(1L, 2L));
    }

    @Test
    void productIds가_빈_목록이면_400이고_핸들러를_호출하지_않는다() throws Exception {
        mockMvc.perform(post("/api/replenishments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[]}"))
                .andExpect(status().isBadRequest());

        verify(stockReplenishedHandler, never()).onReplenished(any());
    }

    @Test
    void productIds가_누락되면_400이고_핸들러를_호출하지_않는다() throws Exception {
        mockMvc.perform(post("/api/replenishments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(stockReplenishedHandler, never()).onReplenished(any());
    }

    @Test
    void 승격_중_WMS_통신이_실패하면_503을_반환한다() throws Exception {
        doThrow(new ResourceAccessException("WMS down"))
                .when(stockReplenishedHandler).onReplenished(any());

        mockMvc.perform(post("/api/replenishments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[1]}"))
                .andExpect(status().isServiceUnavailable());
    }
}
