package com.jhg.hgpage.api;

import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.oms.dto.OrderDto;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.oms.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 메인 "내 주문" 새로고침 버튼이 fetch로 호출하는 주문 목록 JSON API.
 */
@WebMvcTest(OrderApiController.class)
class OrderApiControllerMvcTest {

    @Autowired MockMvc mockMvc;

    @MockBean OrderService orderService;

    private UserPrincipal principal() {
        return new UserPrincipal(1L, "user@example.com", "테스터", "010-0000-0000", "password", Role.USER);
    }

    @Test
    void 내_주문_목록을_JSON으로_반환한다() throws Exception {
        when(orderService.findOrders(1L)).thenReturn(List.of(
                new OrderDto(101L, OrderStatus.ORDER, 23000, LocalDateTime.of(2026, 6, 12, 10, 22))));

        mockMvc.perform(get("/api/orders/me").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(101))
                .andExpect(jsonPath("$[0].status").value("ORDER"))
                .andExpect(jsonPath("$[0].totalAmount").value(23000))
                .andExpect(jsonPath("$[0].createdAt").value(startsWith("2026-06-12T10:22")));
    }

    @Test
    void 주문이_없으면_빈_배열을_반환한다() throws Exception {
        when(orderService.findOrders(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/orders/me").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
