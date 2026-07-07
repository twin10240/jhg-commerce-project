package com.jhg.hgpage.controller.admin;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.wms.adapter.WmsInventoryAdapter;
import com.jhg.hgpage.wms.adapter.WmsInventoryQueryAdapter;
import com.jhg.hgpage.wms.adapter.WmsPurchaseOrderAdapter;
import com.jhg.hgpage.wms.dto.InventoryRow;
import com.jhg.hgpage.wms.dto.PurchaseOrderDto;
import com.jhg.hgpage.wms.web.controller.InventoryAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryAdminController.class)
@Import(SecurityConfig.class)
class InventoryAdminControllerMvcTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean WmsInventoryAdapter wmsInventoryAdapter;
    @MockitoBean WmsInventoryQueryAdapter wmsInventoryQueryAdapter;
    @MockitoBean WmsPurchaseOrderAdapter wmsPurchaseOrderAdapter;

    private UserPrincipal admin() {
        return new UserPrincipal(2L, "admin@admin.com", "관리자", "010-1111-2222", "pw", Role.ADMIN);
    }

    private UserPrincipal normalUser() {
        return new UserPrincipal(1L, "u@u.com", "유저", "010-0000-0000", "pw", Role.USER);
    }

    @Test
    void 재고화면은_재고목록을_조회한다() throws Exception {
        when(wmsInventoryQueryAdapter.allRows()).thenReturn(List.of(new InventoryRow(1L, 15)));

        mockMvc.perform(get("/admin/inventory").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inventory"))
                .andExpect(model().attributeExists("products"));
    }

    @Test
    void 발주화면은_발주목록과_재고목록을_조회한다() throws Exception {
        when(wmsInventoryQueryAdapter.allRows()).thenReturn(List.of(new InventoryRow(1L, 15)));
        when(wmsPurchaseOrderAdapter.findAllWithItems()).thenReturn(List.of(
                new PurchaseOrderDto(1L, "ORDERED", "긴급", null, null, List.of())));

        mockMvc.perform(get("/admin/purchase-orders").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/purchaseorders"))
                .andExpect(model().attributeExists("purchaseOrders", "products"));
    }

    @Test
    void 일반사용자는_재고목록에_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/admin/inventory").with(user(normalUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    void 재고를_조정하면_조회페이지로_리다이렉트한다() throws Exception {
        when(wmsInventoryAdapter.adjust(1L, 5, "정기조사")).thenReturn(20);

        mockMvc.perform(post("/admin/inventory/adjust")
                        .with(user(admin())).with(csrf())
                        .param("productId", "1").param("delta", "5").param("reason", "정기조사"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(wmsInventoryAdapter).adjust(1L, 5, "정기조사");
    }


    @Test
    void 재고_조정_실패는_에러메시지와_함께_리다이렉트한다() throws Exception {
        when(wmsInventoryAdapter.adjust(1L, -99, "조정"))
                .thenThrow(new IllegalArgumentException("재고는 0 미만이 될 수 없습니다."));

        mockMvc.perform(post("/admin/inventory/adjust")
                        .with(user(admin())).with(csrf())
                        .param("productId", "1").param("delta", "-99").param("reason", "조정"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void 발주를_생성하면_성공메시지와_함께_리다이렉트한다() throws Exception {
        when(wmsPurchaseOrderAdapter.create(anyList(), eq("긴급 발주"))).thenReturn(7L);

        mockMvc.perform(post("/admin/purchase-orders")
                        .with(user(admin())).with(csrf())
                        .param("items[0].productId", "1")
                        .param("items[0].quantity", "10")
                        .param("memo", "긴급 발주"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/purchase-orders"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(wmsPurchaseOrderAdapter).create(anyList(), eq("긴급 발주"));
    }

    @Test
    void 입고하면_성공메시지와_함께_리다이렉트한다() throws Exception {
        when(wmsPurchaseOrderAdapter.receive(7L)).thenReturn(7L);

        mockMvc.perform(post("/admin/purchase-orders/receive")
                        .with(user(admin())).with(csrf())
                        .param("poId", "7"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/purchase-orders"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(wmsPurchaseOrderAdapter).receive(7L);
    }

    @Test
    void 이미_입고된_발주는_에러메시지와_함께_리다이렉트한다() throws Exception {
        when(wmsPurchaseOrderAdapter.receive(7L))
                .thenThrow(new IllegalStateException("이미 입고 처리된 발주입니다."));

        mockMvc.perform(post("/admin/purchase-orders/receive")
                        .with(user(admin())).with(csrf())
                        .param("poId", "7"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));
    }
}
