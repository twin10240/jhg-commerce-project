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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

@WebMvcTest(ReplenishmentApiController.class)
@Import(SecurityConfig.class)
class ReplenishmentApiControllerMvcTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean StockReplenishedHandler stockReplenishedHandler;

    @Test
    void 인증_없는_콜백은_401이고_핸들러를_호출하지_않는다() throws Exception {
        mockMvc.perform(post("/api/replenishments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[1]}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(stockReplenishedHandler);
    }

    @Test
    void 잘못된_Basic_인증은_401이고_핸들러를_호출하지_않는다() throws Exception {
        mockMvc.perform(post("/api/replenishments")
                        .with(httpBasic("wms", "wrong"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[1]}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(stockReplenishedHandler);
    }

    @Test
    void Basic_인증된_콜백을_핸들러에_위임한다() throws Exception {
        mockMvc.perform(post("/api/replenishments")
                        .with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[1,2]}"))
                .andExpect(status().isOk());

        verify(stockReplenishedHandler).onReplenished(List.of(1L, 2L));
    }

    @Test
    void productIds가_빈_목록이면_400이고_핸들러를_호출하지_않는다() throws Exception {
        mockMvc.perform(post("/api/replenishments")
                        .with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[]}"))
                .andExpect(status().isBadRequest());

        verify(stockReplenishedHandler, never()).onReplenished(any());
    }

    @Test
    void productIds가_누락되면_400이고_핸들러를_호출하지_않는다() throws Exception {
        mockMvc.perform(post("/api/replenishments")
                        .with(httpBasic("wms", "wms"))
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
                        .with(httpBasic("wms", "wms"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[1]}"))
                .andExpect(status().isServiceUnavailable());
    }
}
