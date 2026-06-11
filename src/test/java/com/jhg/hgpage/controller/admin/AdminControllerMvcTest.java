package com.jhg.hgpage.controller.admin;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.domain.Inventory;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.service.InventoryService;
import com.jhg.hgpage.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
class AdminControllerMvcTest {

    @Autowired MockMvc mockMvc;

    @MockBean InventoryService inventoryService;
    @MockBean ProductService productService;

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
    void 관리자는_재고목록을_조회한다() throws Exception {
        when(productService.findAllWithInventory()).thenReturn(List.of(sampleProduct()));

        mockMvc.perform(get("/admin/inventory").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/inventory"))
                .andExpect(model().attributeExists("products"));
    }

    @Test
    void 일반사용자는_재고목록에_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/admin/inventory").with(user(normalUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    void 재고를_조정하면_조회페이지로_리다이렉트하고_성공메시지를_담는다() throws Exception {
        when(inventoryService.adjust(1L, 5, "정기조사")).thenReturn(20);

        mockMvc.perform(post("/admin/inventory/adjust")
                        .with(user(admin()))
                        .with(csrf())
                        .param("productId", "1")
                        .param("delta", "5")
                        .param("reason", "정기조사"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/inventory"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(inventoryService).adjust(1L, 5, "정기조사");
    }

    @Test
    void 재고가_음수가_되는_조정은_에러메시지와_함께_리다이렉트한다() throws Exception {
        when(inventoryService.adjust(1L, -99, "조정"))
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

        verify(inventoryService, never()).adjust(anyLong(), anyInt(), anyString());
    }
}
