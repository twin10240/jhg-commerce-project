package com.jhg.hgpage.controller.main;

import com.jhg.hgpage.domain.Inventory;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.service.MemberService;
import com.jhg.hgpage.service.OrderService;
import com.jhg.hgpage.service.ProductService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(MainController.class)
class MainControllerMvcTest {

    @Autowired MockMvc mockMvc;

    @MockBean MemberService memberService;
    @MockBean ProductService productService;
    @MockBean OrderService orderService;

    private UserPrincipal userPrincipal() {
        return new UserPrincipal(1L, "user@example.com", "테스터", "010-0000-0000", "password", Role.USER);
    }

    private UserPrincipal adminPrincipal() {
        return new UserPrincipal(2L, "admin@admin.com", "관리자", "010-1111-2222", "password", Role.ADMIN);
    }

    private Product sampleProduct() {
        return productWithStock(50);
    }

    private Product productWithStock(int stock) {
        Product p = new Product();
        p.setId(1L);
        p.setName("상품1");
        p.setPrice(10000);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(stock);
        p.setInventory(inventory);
        return p;
    }

    private Page<Product> pageOf(Product product) {
        return new PageImpl<>(List.of(product), PageRequest.of(0, 10, Sort.by("id")), 1);
    }

    @Test
    void 가용재고가_없는_상품은_입고대기로_표시되고_주문은_가능하다() throws Exception {
        when(productService.findPage(eq(""), any(Pageable.class))).thenReturn(pageOf(productWithStock(0)));
        when(orderService.findOrders(1L)).thenReturn(List.of());

        mockMvc.perform(get("/main").with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("입고 대기")))
                .andExpect(content().string(not(containsString("품절"))))
                .andExpect(content().string(not(containsString("disabled=\"disabled\"")))); // 버튼 활성화 — 백오더 주문 가능
    }

    @Test
    void 가용재고가_적은_상품은_남은_수량을_보여준다() throws Exception {
        when(productService.findPage(eq(""), any(Pageable.class))).thenReturn(pageOf(productWithStock(3)));
        when(orderService.findOrders(1L)).thenReturn(List.of());

        mockMvc.perform(get("/main").with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("3개 남음")))
                .andExpect(content().string(not(containsString("입고 대기"))));
    }

    @Test
    void 예약이_잡힌_상품은_가용수량_기준으로_표시된다() throws Exception {
        Product product = productWithStock(5);
        product.getInventory().setReservedQty(2); // 가용 3
        when(productService.findPage(eq(""), any(Pageable.class))).thenReturn(pageOf(product));
        when(orderService.findOrders(1L)).thenReturn(List.of());

        mockMvc.perform(get("/main").with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("3개 남음")));
    }

    @Test
    void 가용재고가_충분하면_입고대기와_남은수량을_표시하지_않는다() throws Exception {
        when(productService.findPage(eq(""), any(Pageable.class))).thenReturn(pageOf(productWithStock(50)));
        when(orderService.findOrders(1L)).thenReturn(List.of());

        mockMvc.perform(get("/main").with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("입고 대기"))))
                .andExpect(content().string(not(containsString("개 남음"))));
    }

    @Test
    void main은_keyword와_pageable로_상품페이지를_조회한다() throws Exception {
        Page<Product> page = new PageImpl<>(List.of(sampleProduct()), PageRequest.of(1, 4, Sort.by("id")), 9);
        when(productService.findPage(eq("상품"), any(Pageable.class))).thenReturn(page);
        when(orderService.findOrders(1L)).thenReturn(List.of());

        mockMvc.perform(get("/main")
                        .param("keyword", "상품")
                        .param("page", "1")
                        .param("size", "4")
                        .with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(view().name("main"))
                .andExpect(model().attributeExists("productPage", "orders"))
                .andExpect(model().attribute("keyword", "상품"));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(productService).findPage(eq("상품"), pageableCaptor.capture());

        Pageable used = pageableCaptor.getValue();
        assertThat(used.getPageNumber()).isEqualTo(1);
        assertThat(used.getPageSize()).isEqualTo(4);
        assertThat(used.getSort().getOrderFor("id")).isNotNull();
    }

    @Test
    void main은_keyword가_없으면_기본_페이지로_조회한다() throws Exception {
        when(productService.findPage(eq(""), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(orderService.findOrders(1L)).thenReturn(List.of());

        mockMvc.perform(get("/main").with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(view().name("main"))
                .andExpect(model().attribute("keyword", ""));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(productService).findPage(eq(""), pageableCaptor.capture());

        // @PageableDefault(size = 10)
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
    }

    @Test
    void 페이지_윈도우는_현재_페이지를_중심으로_최대_5개를_노출한다() throws Exception {
        // 총 120건 / size 10 = 12페이지, 현재 0-based 4페이지 → 윈도우 2~6 (표시 3~7)
        Page<Product> page = new PageImpl<>(List.of(sampleProduct()), PageRequest.of(4, 10, Sort.by("id")), 120);
        when(productService.findPage(eq(""), any(Pageable.class))).thenReturn(page);
        when(orderService.findOrders(1L)).thenReturn(List.of());

        mockMvc.perform(get("/main").param("page", "4").with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("beginPage", 2))
                .andExpect(model().attribute("endPage", 6));
    }

    @Test
    void 마지막_페이지에서는_윈도우가_끝에_붙는다() throws Exception {
        // 12페이지 중 현재 0-based 11페이지 → 윈도우 7~11 (표시 8~12)
        Page<Product> page = new PageImpl<>(List.of(sampleProduct()), PageRequest.of(11, 10, Sort.by("id")), 120);
        when(productService.findPage(eq(""), any(Pageable.class))).thenReturn(page);
        when(orderService.findOrders(1L)).thenReturn(List.of());

        mockMvc.perform(get("/main").param("page", "11").with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("beginPage", 7))
                .andExpect(model().attribute("endPage", 11));
    }

    @Test
    void 총_페이지가_5개_이하면_윈도우는_전체_페이지다() throws Exception {
        // 20건 / size 10 = 2페이지 → 윈도우 0~1
        Page<Product> page = new PageImpl<>(List.of(sampleProduct()), PageRequest.of(0, 10, Sort.by("id")), 20);
        when(productService.findPage(eq(""), any(Pageable.class))).thenReturn(page);
        when(orderService.findOrders(1L)).thenReturn(List.of());

        mockMvc.perform(get("/main").with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("beginPage", 0))
                .andExpect(model().attribute("endPage", 1));
    }

    @Test
    void 일반사용자는_inventoryProducts를_조회하지_않는다() throws Exception {
        when(productService.findPage(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(orderService.findOrders(1L)).thenReturn(List.of());

        mockMvc.perform(get("/main").with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("inventoryProducts"));

        verify(productService, never()).findAllWithInventory();
    }

    @Test
    void 메인에는_주문_새로고침_fetch_배선이_포함된다() throws Exception {
        when(productService.findPage(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(orderService.findOrders(1L)).thenReturn(List.of());

        mockMvc.perform(get("/main").with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/orders/me")))
                .andExpect(content().string(containsString("ordersTbody")));
    }

    @Test
    void 관리자는_inventoryProducts를_조회한다() throws Exception {
        when(productService.findPage(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(productService.findAllWithInventory()).thenReturn(List.of(sampleProduct()));
        when(orderService.findOrders(2L)).thenReturn(List.of());

        mockMvc.perform(get("/main").with(user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("inventoryProducts"));

        verify(productService).findAllWithInventory();
    }
}
