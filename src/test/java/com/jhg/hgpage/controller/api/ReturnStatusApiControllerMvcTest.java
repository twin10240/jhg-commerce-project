package com.jhg.hgpage.controller.api;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.contract.ReturnPort.ReturnResult;
import com.jhg.hgpage.contract.ReturnPort.ResultItem;
import com.jhg.hgpage.oms.service.ReturnSyncService;
import com.jhg.hgpage.oms.web.api.ReturnStatusApiController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReturnStatusApiController.class)
@Import(SecurityConfig.class)
class ReturnStatusApiControllerMvcTest {

    private static final UUID REQUEST_KEY = UUID.fromString("c7c2c824-bf62-4d39-bb55-e2f1adf9083b");

    @Autowired MockMvc mockMvc;
    @MockitoBean ReturnSyncService returnSyncService;

    @Test
    void COMPLETED_콜백을_동기화_서비스에_위임한다() throws Exception {
        mockMvc.perform(callback("COMPLETED", "RESTOCKED", 1))
                .andExpect(status().isOk());

        verify(returnSyncService).apply(result("COMPLETED", "RESTOCKED", 1));
    }

    @Test
    void CANCELLED_콜백을_동기화_서비스에_위임한다() throws Exception {
        mockMvc.perform(callback("CANCELLED", null, 0))
                .andExpect(status().isOk());

        verify(returnSyncService).apply(result("CANCELLED", null, 0));
    }

    @Test
    void 필수_구조가_null인_콜백은_400이다() throws Exception {
        for (String body : List.of(
                "{\"rmaId\":null,\"requestKey\":\"" + REQUEST_KEY + "\",\"orderId\":40,\"status\":\"COMPLETED\",\"items\":[]}",
                "{\"rmaId\":30,\"requestKey\":null,\"orderId\":40,\"status\":\"COMPLETED\",\"items\":[]}",
                "{\"rmaId\":30,\"requestKey\":\"" + REQUEST_KEY + "\",\"orderId\":null,\"status\":\"COMPLETED\",\"items\":[]}",
                "{\"rmaId\":30,\"requestKey\":\"" + REQUEST_KEY + "\",\"orderId\":40,\"status\":null,\"items\":[]}",
                "{\"rmaId\":30,\"requestKey\":\"" + REQUEST_KEY + "\",\"orderId\":40,\"status\":\"COMPLETED\",\"items\":null}")) {
            mockMvc.perform(post("/api/return-status-events")
                            .with(httpBasic("wms", "wms"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        verify(returnSyncService, never()).apply(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 계약이_맞지_않는_콜백은_409이다() throws Exception {
        doThrow(new ReturnSyncService.ReturnContractMismatchException())
                .when(returnSyncService).apply(result("COMPLETED", "RESTOCKED", 1));

        mockMvc.perform(callback("COMPLETED", "RESTOCKED", 1))
                .andExpect(status().isConflict());
    }

    @Test
    void 중복된_정상_콜백도_200이다() throws Exception {
        mockMvc.perform(callback("COMPLETED", "RESTOCKED", 1))
                .andExpect(status().isOk());
        mockMvc.perform(callback("COMPLETED", "RESTOCKED", 1))
                .andExpect(status().isOk());

        verify(returnSyncService, org.mockito.Mockito.times(2)).apply(result("COMPLETED", "RESTOCKED", 1));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder callback(
            String status, String disposition, int acceptedQuantity) {
        String dispositionJson = disposition == null ? "null" : "\"" + disposition + "\"";
        return post("/api/return-status-events")
                .with(httpBasic("wms", "wms"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"rmaId":30,"requestKey":"%s","orderId":40,"status":"%s","items":[{"orderItemId":50,"productId":60,"requestedQuantity":1,"acceptedQuantity":%d,"disposition":%s}]}
                        """.formatted(REQUEST_KEY, status, acceptedQuantity, dispositionJson));
    }

    private ReturnResult result(String status, String disposition, int acceptedQuantity) {
        return new ReturnResult(30L, REQUEST_KEY, 40L, status,
                List.of(new ResultItem(50L, 60L, 1, acceptedQuantity, disposition)));
    }
}
