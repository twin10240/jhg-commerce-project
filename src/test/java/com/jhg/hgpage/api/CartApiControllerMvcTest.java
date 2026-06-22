package com.jhg.hgpage.api;
import com.jhg.hgpage.oms.web.api.CartApiController;

import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.exception.NotEnoughStockException;
import com.jhg.hgpage.oms.service.CartService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 장바구니 REST API 예외 응답 검증.
 * GlobalExceptionHandler가 /api/** 요청에는 ProblemDetail JSON을 반환해야 한다.
 */
@WebMvcTest(CartApiController.class)
class CartApiControllerMvcTest {

    @Autowired MockMvc mockMvc;

    @MockBean CartService cartService;

    private UserPrincipal principal() {
        return new UserPrincipal(1L, "user@example.com", "테스터", "010-0000-0000", "password", Role.USER);
    }

    @Test
    void 없는_상품을_담으면_404_JSON을_반환한다() throws Exception {
        when(cartService.addCartItem(1L, 99L, 1))
                .thenThrow(new EntityNotFoundException("Product", 99L));

        mockMvc.perform(post("/api/cart/items")
                        .with(user(principal()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":99,\"qty\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Product not found: id=99"));
    }

    @Test
    void 장바구니에_없는_상품의_수량을_변경하면_400_JSON을_반환한다() throws Exception {
        doThrow(new IllegalArgumentException("Cart item not found."))
                .when(cartService).updateCartItemQuantity(1L, 5L, 3);

        mockMvc.perform(patch("/api/cart/items/5")
                        .with(user(principal()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Cart item not found."));
    }

    @Test
    void 재고부족이면_409_JSON을_반환한다() throws Exception {
        when(cartService.addCartItem(1L, 1L, 999))
                .thenThrow(new NotEnoughStockException("need more stock"));

        mockMvc.perform(post("/api/cart/items")
                        .with(user(principal()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"qty\":999}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("need more stock"));
    }
}
