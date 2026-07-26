package com.jhg.hgpage.controller.admin;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.wms.adapter.WmsInventoryQueryAdapter;
import com.jhg.hgpage.wms.adapter.WmsReplenishmentRequestAdapter;
import com.jhg.hgpage.wms.adapter.WmsReplenishmentRequestAdapter.RequestLine;
import com.jhg.hgpage.wms.dto.InventoryRow;
import com.jhg.hgpage.wms.dto.ReplenishmentRequestDto;
import com.jhg.hgpage.wms.web.controller.InventoryAdminController;
import com.jhg.hgpage.wms.web.form.ReplenishmentRequestForm;
import com.jhg.hgpage.oms.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryAdminController.class)
@Import(SecurityConfig.class)
class InventoryAdminControllerMvcTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean WmsInventoryQueryAdapter wmsInventoryQueryAdapter;
    @MockitoBean WmsReplenishmentRequestAdapter requestAdapter;
    @MockitoBean OrderService orderService;

    private UserPrincipal admin() {
        return new UserPrincipal(2L, "admin@admin.com", "admin", "010-1111-2222", "pw", Role.ADMIN);
    }

    private UserPrincipal normalUser() {
        return new UserPrincipal(1L, "u@u.com", "user", "010-0000-0000", "pw", Role.USER);
    }

    @Test
    void inventorySplitsBackorderDemandIntoInboundAndAllocationWaitingQuantities() throws Exception {
        var products = List.of(
                new InventoryRow(1L, "상품 1", 20, 2, 18),
                new InventoryRow(3L, "상품 3", 45, 0, 45),
                new InventoryRow(4L, "상품 4", 60, 0, 60),
                new InventoryRow(10L, "상품 10", 150, 0, 150));
        when(wmsInventoryQueryAdapter.allRows()).thenReturn(products);
        when(orderService.backorderDemandByProductId(List.of(1L, 3L, 4L, 10L)))
                .thenReturn(java.util.Map.of(1L, 20, 3L, 2, 4L, 1, 10L, 1));
        when(requestAdapter.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/admin/inventory").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inventory"))
                .andExpect(model().attribute("products", products))
                .andExpect(model().attribute("backorderQty", 24))
                .andExpect(model().attribute("inboundRequiredQty", 2))
                .andExpect(model().attribute("allocationWaitingQty", 22))
                .andExpect(model().attribute("pendingRequests", java.util.Map.of()))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("백오더 총수량")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("입고 필요 수량")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("할당 대기 수량")))
                .andExpect(model().attributeDoesNotExist("requests", "requestForm"));

        verify(requestAdapter).findAll();
    }

    @Test
    void inventoryShowsConnectionErrorInsteadOfZeroStockWhenWmsIsDown() throws Exception {
        when(wmsInventoryQueryAdapter.allRows()).thenThrow(new ResourceAccessException("WMS down"));

        mockMvc.perform(get("/admin/inventory").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inventory"))
                .andExpect(model().attribute("inventoryUnavailable", true))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("WMS에 연결할 수 없어")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("가용재고 0 상품"))));

        verifyNoInteractions(orderService, requestAdapter);
    }

    @Test
    void replenishmentRequestsShowsProductsRequestsAndNewForm() throws Exception {
        var request = request(UUID.randomUUID());
        when(wmsInventoryQueryAdapter.allRows()).thenReturn(List.of(new InventoryRow(1L, "상품 1", 15, 3, 12)));
        when(requestAdapter.findAll()).thenReturn(List.of(request));

        var result = mockMvc.perform(get("/admin/replenishment-requests").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/replenishment-requests"))
                .andExpect(model().attribute("products", List.of(new InventoryRow(1L, "상품 1", 15, 3, 12))))
                .andExpect(model().attribute("requests", List.of(request)))
                .andExpect(model().attributeExists("requestForm"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("상품 1 (#1) · 가용 12개")))
                .andReturn();

        ReplenishmentRequestForm form = (ReplenishmentRequestForm) result.getModelAndView().getModel().get("requestForm");
        assertThat(form.getRequestKey()).isNotNull();
        assertThat(form.getItems()).hasSize(1);
    }

    @Test
    void inventoryRowLinkPrefillsReplenishmentProduct() throws Exception {
        when(wmsInventoryQueryAdapter.allRows()).thenReturn(
                List.of(new InventoryRow(1L, "상품 1", 15, 3, 12)));
        when(requestAdapter.findAll()).thenReturn(List.of());

        var result = mockMvc.perform(get("/admin/replenishment-requests")
                        .param("productId", "1")
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andReturn();

        ReplenishmentRequestForm form = (ReplenishmentRequestForm) result.getModelAndView().getModel().get("requestForm");
        assertThat(form.getItems().get(0).getProductId()).isEqualTo(1L);
    }

    @Test
    void createsRequestWithStableKeyAndLines() throws Exception {
        UUID key = UUID.randomUUID();
        when(requestAdapter.create(key, List.of(new RequestLine(1L, 3)), "low stock"))
                .thenReturn(request(key));

        mockMvc.perform(post("/admin/replenishment-requests")
                        .with(user(admin())).with(csrf())
                        .param("requestKey", key.toString())
                        .param("reason", "low stock")
                        .param("items[0].productId", "1")
                        .param("items[0].requestedQty", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/replenishment-requests"))
                .andExpect(flash().attribute(
                        "successMessage", "재고 보충 요청이 WMS에 접수되었습니다."));

        verify(requestAdapter).create(key, List.of(new RequestLine(1L, 3)), "low stock");
    }

    @Test
    void adapterErrorPreservesRequestForm() throws Exception {
        UUID key = UUID.randomUUID();
        when(requestAdapter.create(key, List.of(new RequestLine(1L, 3)), "low stock"))
                .thenThrow(new IllegalStateException("WMS unavailable"));

        var result = mockMvc.perform(post("/admin/replenishment-requests")
                        .with(user(admin())).with(csrf())
                        .param("requestKey", key.toString())
                        .param("reason", "low stock")
                        .param("items[0].productId", "1")
                        .param("items[0].requestedQty", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/replenishment-requests"))
                .andExpect(flash().attribute("errorMessage", "WMS unavailable"))
                .andExpect(flash().attributeExists("requestForm"))
                .andReturn();

        ReplenishmentRequestForm form = (ReplenishmentRequestForm) result.getFlashMap().get("requestForm");
        assertThat(form.getRequestKey()).isEqualTo(key);
        assertThat(form.getReason()).isEqualTo("low stock");
        assertThat(form.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getProductId()).isEqualTo(1L);
            assertThat(item.getRequestedQty()).isEqualTo(3);
        });
    }

    @Test
    void normalUserCannotViewInventory() throws Exception {
        mockMvc.perform(get("/admin/inventory").with(user(normalUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    void normalUserCannotViewReplenishmentRequests() throws Exception {
        mockMvc.perform(get("/admin/replenishment-requests").with(user(normalUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    void normalUserCannotCreateRequest() throws Exception {
        mockMvc.perform(post("/admin/replenishment-requests").with(user(normalUser())).with(csrf()))
                .andExpect(status().isForbidden());
    }

    private ReplenishmentRequestDto request(UUID key) {
        return new ReplenishmentRequestDto(11L, key, "low stock", "REQUESTED",
                LocalDateTime.of(2026, 7, 16, 10, 0), null, null, null, null,
                List.of(new ReplenishmentRequestDto.ItemDto(1L, 3)));
    }
}
