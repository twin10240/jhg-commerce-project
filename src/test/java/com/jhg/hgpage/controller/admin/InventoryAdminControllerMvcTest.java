package com.jhg.hgpage.controller.admin;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.wms.domain.PurchaseOrder;
import com.jhg.hgpage.wms.domain.PurchaseOrderItem;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.wms.service.InventoryAdjustmentService;
import com.jhg.hgpage.catalog.ProductService;
import com.jhg.hgpage.wms.service.PurchaseOrderService;
import com.jhg.hgpage.wms.web.controller.InventoryAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(InventoryAdminController.class)
@Import(SecurityConfig.class)
class InventoryAdminControllerMvcTest {

    @Autowired MockMvc mockMvc;

    @MockBean InventoryAdjustmentService inventoryAdjustmentService;
    @MockBean ProductService productService;
    @MockBean PurchaseOrderService purchaseOrderService;

    private UserPrincipal admin() {
        return new UserPrincipal(2L, "admin@admin.com", "관리자", "010-1111-2222", "password", Role.ADMIN);
    }

    private UserPrincipal normalUser() {
        return new UserPrincipal(1L, "user@example.com", "테스터", "010-0000-0000", "password", Role.USER);
    }

    private Product sampleProduct() {
        Product product = new Product();
        product.setId(1L);
        product.setName("상품1");
        product.setPrice(10000);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(15);
        product.setInventory(inventory);
        return product;
    }

    @Test
    void 관리자는_재고목록과_발주현황을_조회한다() throws Exception {
        when(productService.findAllWithInventory()).thenReturn(List.of(sampleProduct()));
        when(purchaseOrderService.findAllWithItems()).thenReturn(
                List.of(PurchaseOrder.create("긴급 발주", PurchaseOrderItem.create(sampleProduct(), 5))));

        mockMvc.perform(get("/admin/inventory").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inventory"))
                .andExpect(model().attributeExists("products", "purchaseOrders"));
    }

    @Test
    void 일반사용자는_재고목록에_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/admin/inventory").with(user(normalUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    void 재고를_조정하면_조회페이지로_리다이렉트하고_성공메시지를_담는다() throws Exception {
        when(inventoryAdjustmentService.adjust(1L, 5, "정기조사")).thenReturn(20);

        mockMvc.perform(post("/admin/inventory/adjust")
                        .with(user(admin()))
                        .with(csrf())
                        .param("productId", "1")
                        .param("delta", "5")
                        .param("reason", "정기조사"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(inventoryAdjustmentService).adjust(1L, 5, "정기조사");
    }

    @Test
    void 재고가_음수가_되는_조정은_에러메시지와_함께_리다이렉트한다() throws Exception {
        when(inventoryAdjustmentService.adjust(1L, -99, "조정"))
                .thenThrow(new IllegalArgumentException("재고는 0 미만이 될 수 없습니다."));

        mockMvc.perform(post("/admin/inventory/adjust")
                        .with(user(admin()))
                        .with(csrf())
                        .param("productId", "1")
                        .param("delta", "-99")
                        .param("reason", "조정"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void 일반사용자는_재고를_조정할_수_없다() throws Exception {
        mockMvc.perform(post("/admin/inventory/adjust")
                        .with(user(normalUser()))
                        .with(csrf())
                        .param("productId", "1")
                        .param("delta", "5")
                        .param("reason", "조정"))
                .andExpect(status().isForbidden());

        verify(inventoryAdjustmentService, never()).adjust(anyLong(), anyInt(), anyString());
    }

    @Test
    void 발주를_생성하면_성공메시지와_함께_재고페이지로_리다이렉트한다() throws Exception {
        when(purchaseOrderService.create(anyList(), eq("긴급 발주"))).thenReturn(7L);

        mockMvc.perform(post("/admin/purchase-orders")
                        .with(user(admin()))
                        .with(csrf())
                        .param("items[0].productId", "1")
                        .param("items[0].quantity", "10")
                        .param("memo", "긴급 발주"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(purchaseOrderService).create(anyList(), eq("긴급 발주"));
    }

    @Test
    void 잘못된_발주는_에러메시지와_함께_리다이렉트한다() throws Exception {
        when(purchaseOrderService.create(anyList(), anyString()))
                .thenThrow(new IllegalArgumentException("발주 수량은 1개 이상이어야 합니다."));

        mockMvc.perform(post("/admin/purchase-orders")
                        .with(user(admin()))
                        .with(csrf())
                        .param("items[0].productId", "1")
                        .param("items[0].quantity", "0")
                        .param("memo", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void 입고하면_성공메시지와_함께_재고페이지로_리다이렉트한다() throws Exception {
        when(purchaseOrderService.receive(7L)).thenReturn(7L);

        mockMvc.perform(post("/admin/purchase-orders/receive")
                        .with(user(admin()))
                        .with(csrf())
                        .param("poId", "7"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(purchaseOrderService).receive(7L);
    }

    @Test
    void 이미_입고된_발주는_에러메시지와_함께_리다이렉트한다() throws Exception {
        when(purchaseOrderService.receive(7L))
                .thenThrow(new IllegalStateException("이미 입고 처리된 발주입니다."));

        mockMvc.perform(post("/admin/purchase-orders/receive")
                        .with(user(admin()))
                        .with(csrf())
                        .param("poId", "7"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void 없는_발주를_입고하면_에러메시지와_함께_리다이렉트한다() throws Exception {
        when(purchaseOrderService.receive(99L))
                .thenThrow(new com.jhg.hgpage.exception.EntityNotFoundException("PurchaseOrder", 99L));

        mockMvc.perform(post("/admin/purchase-orders/receive")
                        .with(user(admin()))
                        .with(csrf())
                        .param("poId", "99"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attributeExists("errorMessage"));
    }
}
