package com.jhg.hgpage.controller.order;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.catalog.ProductRepository;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.oms.service.MemberService;
import com.jhg.hgpage.oms.service.OrderService;
import com.jhg.hgpage.oms.web.controller.OrderController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 구매 경로(/orders/**)는 고객(USER) 전용 — admin은 운영자라 주문할 수 없어야 한다(403).
 * SecurityConfig 의 인가 규칙을 적용해 검증한다(기본 시큐리티 슬라이스로는 역할 규칙이 안 걸림).
 */
@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
class OrderAccessControlMvcTest {

    @Autowired MockMvc mockMvc;

    @MockBean MemberService memberService;
    @MockBean ProductRepository productRepository;
    @MockBean OrderService orderService;

    private UserPrincipal adminPrincipal() {
        return new UserPrincipal(99L, "admin@admin.com", "관리자", "010-1111-2222", "password", Role.ADMIN);
    }

    @Test
    void admin은_주문서_생성이_403으로_차단된다() throws Exception {
        mockMvc.perform(post("/orders/checkout-form")
                        .with(user(adminPrincipal()))
                        .with(csrf())
                        .param("productId", "1")
                        .param("qty", "1"))
                .andExpect(status().isForbidden());

        // 인가 필터에서 막혀 컨트롤러/서비스까지 도달하지 않음
        verify(orderService, never()).order(anyLong(), any(), anyList());
    }
}
