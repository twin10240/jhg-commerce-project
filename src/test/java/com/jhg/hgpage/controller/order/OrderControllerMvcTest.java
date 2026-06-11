package com.jhg.hgpage.controller.order;

import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Inventory;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.exception.NotEnoughStockException;
import com.jhg.hgpage.repository.ProductRepository;
import com.jhg.hgpage.service.MemberService;
import com.jhg.hgpage.service.OrderService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 컨트롤러 슬라이스 통합 테스트.
 * 단위 테스트(OrderControllerTest)와 달리 실제 @Valid 바인딩 + 시큐리티 + 뷰/모델 처리를 거쳐
 * /orders/checkout 의 엔드투엔드 분기를 검증한다.
 */
@WebMvcTest(OrderController.class)
class OrderControllerMvcTest {

    @Autowired MockMvc mockMvc;

    @MockBean MemberService memberService;
    @MockBean ProductRepository productRepository;
    @MockBean OrderService orderService;

    private UserPrincipal principal() {
        return new UserPrincipal(1L, "user@example.com", "테스터", "010-0000-0000", "password", Role.USER);
    }

    @Test
    void 빈주문이면_주문하지않고_orderdetail로_돌아간다() throws Exception {
        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "500"))
                .andExpect(status().isOk())
                .andExpect(view().name("orderdetail"))
                .andExpect(model().attributeHasFieldErrors("checkout", "product"));

        verify(orderService, never()).order(anyLong(), any(Address.class), anyList());
    }

    @Test
    void 수량이_0이면_orderdetail로_돌아간다() throws Exception {
        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "500")
                        .param("product[0].id", "1")
                        .param("product[0].quantity", "0"))
                .andExpect(status().isOk())
                .andExpect(view().name("orderdetail"))
                .andExpect(model().attributeHasFieldErrors("checkout", "product[0].quantity"));

        verify(orderService, never()).order(anyLong(), any(Address.class), anyList());
    }

    @Test
    void 정상주문이면_주문하고_main으로_리다이렉트한다() throws Exception {
        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "500")
                        .param("product[0].id", "1")
                        .param("product[0].quantity", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/main"));

        verify(orderService).order(eq(1L), any(Address.class), anyList());
    }

    @Test
    void 선택된_상품만_주문된다() throws Exception {
        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "500")
                        .param("product[0].id", "1")
                        .param("product[0].quantity", "2")
                        .param("product[0].selected", "true")
                        .param("product[1].id", "2")
                        .param("product[1].quantity", "3")
                        .param("product[1].selected", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/main"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrderService.OrderLine>> linesCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(orderService).order(eq(1L), any(Address.class), linesCaptor.capture());

        List<OrderService.OrderLine> lines = linesCaptor.getValue();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).productId()).isEqualTo(1L);
        assertThat(lines.get(0).quantity()).isEqualTo(2);
    }

    @Test
    void 아무것도_선택하지_않으면_주문하지않고_orderdetail로_돌아간다() throws Exception {
        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "500")
                        .param("product[0].id", "1")
                        .param("product[0].quantity", "2")
                        .param("product[0].selected", "false")
                        .param("product[1].id", "2")
                        .param("product[1].quantity", "3")
                        .param("product[1].selected", "false"))
                .andExpect(status().isOk())
                .andExpect(view().name("orderdetail"))
                .andExpect(model().attributeHasFieldErrors("checkout", "product"));

        verify(orderService, never()).order(anyLong(), any(Address.class), anyList());
    }

    @Test
    void 재고가_부족하면_main으로_리다이렉트하고_에러메시지를_flash에_담는다() throws Exception {
        doThrow(new NotEnoughStockException("need more stock"))
                .when(orderService).order(anyLong(), any(Address.class), anyList());

        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "500")
                        .param("product[0].id", "1")
                        .param("product[0].quantity", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/main"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void 재고_수정이_충돌하면_main으로_리다이렉트하고_에러메시지를_flash에_담는다() throws Exception {
        doThrow(new ObjectOptimisticLockingFailureException(Inventory.class, 1L))
                .when(orderService).order(anyLong(), any(Address.class), anyList());

        mockMvc.perform(post("/orders/checkout")
                        .with(user(principal()))
                        .with(csrf())
                        .param("delivery.city", "서울")
                        .param("delivery.street", "관악구")
                        .param("delivery.zipcode", "500")
                        .param("product[0].id", "1")
                        .param("product[0].quantity", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/main"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void 없는_상품으로_주문서를_요청하면_404_에러페이지를_보여준다() throws Exception {
        when(memberService.findMember(1L)).thenReturn(
                Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500")));
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(post("/orders/checkout-form")
                        .with(user(principal()))
                        .with(csrf())
                        .param("productId", "99")
                        .param("qty", "1"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    @Test
    void 화면요청에서_IllegalArgumentException이면_400_에러페이지를_보여준다() throws Exception {
        when(memberService.findMember(1L)).thenThrow(new IllegalArgumentException("invalid request"));

        mockMvc.perform(post("/orders/checkout-form")
                        .with(user(principal()))
                        .with(csrf())
                        .param("productId", "1")
                        .param("qty", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("error"));
    }
}
