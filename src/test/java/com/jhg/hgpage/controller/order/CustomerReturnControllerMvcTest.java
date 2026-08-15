package com.jhg.hgpage.controller.order;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.service.CustomerReturnService;
import com.jhg.hgpage.oms.service.ReturnSubmissionService;
import com.jhg.hgpage.oms.web.controller.CustomerReturnController;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(CustomerReturnController.class)
@Import(SecurityConfig.class)
class CustomerReturnControllerMvcTest {

    @Autowired MockMvc mockMvc;

    @MockBean CustomerReturnService customerReturnService;
    @MockBean ReturnSubmissionService returnSubmissionService;

    private UserPrincipal userPrincipal() {
        return new UserPrincipal(1L, "user@example.com", "테스터", "010-0000-0000", "password", Role.USER);
    }

    private UserPrincipal adminPrincipal() {
        return new UserPrincipal(99L, "admin@example.com", "관리자", "010-1111-1111", "password", Role.ADMIN);
    }

    @Test
    void 양수인_품목만_요청하고_로컬_저장_후_WMS에_전송한다() throws Exception {
        when(customerReturnService.request(eq(10L), eq(1L), eq("사이즈가 맞지 않습니다."), anyList()))
                .thenReturn(77L);

        mockMvc.perform(post("/orders/10/returns")
                        .with(user(userPrincipal()))
                        .with(csrf())
                        .param("reason", "사이즈가 맞지 않습니다.")
                        .param("lines[0].orderItemId", "101")
                        .param("lines[0].quantity", "0")
                        .param("lines[1].orderItemId", "102")
                        .param("lines[1].quantity", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/10"))
                .andExpect(flash().attribute("successMessage", "반품 요청이 저장되었습니다."));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CustomerReturnService.ReturnLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(customerReturnService).request(eq(10L), eq(1L), eq("사이즈가 맞지 않습니다."), lines.capture());
        assertThat(lines.getValue()).containsExactly(new CustomerReturnService.ReturnLine(102L, 2));

        InOrder order = inOrder(customerReturnService, returnSubmissionService);
        order.verify(customerReturnService).request(eq(10L), eq(1L), eq("사이즈가 맞지 않습니다."), anyList());
        order.verify(returnSubmissionService).submit(77L);
    }

    @Test
    void 빈_품목_선택은_폼과_바인딩오류를_flash로_보존한다() throws Exception {
        mockMvc.perform(post("/orders/10/returns")
                        .with(user(userPrincipal()))
                        .with(csrf())
                        .param("reason", "단순 변심")
                        .param("lines[0].orderItemId", "101")
                        .param("lines[0].quantity", "0"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/10"))
                .andExpect(flash().attributeExists("returnForm"))
                .andExpect(flash().attributeExists("org.springframework.validation.BindingResult.returnForm"));

        verify(customerReturnService, never()).request(eq(10L), eq(1L), eq("단순 변심"), anyList());
        verify(returnSubmissionService, never()).submit(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 빈_사유는_PRG로_인라인_오류를_보존한다() throws Exception {
        mockMvc.perform(post("/orders/10/returns")
                        .with(user(userPrincipal()))
                        .with(csrf())
                        .param("reason", "   ")
                        .param("lines[0].orderItemId", "101")
                        .param("lines[0].quantity", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/10"))
                .andExpect(flash().attributeExists("returnForm"))
                .andExpect(flash().attributeExists("org.springframework.validation.BindingResult.returnForm"));

        verify(customerReturnService, never()).request(eq(10L), eq(1L), eq("   "), anyList());
    }

    @Test
    void 서비스_검증실패도_상세화면의_인라인_오류로_돌려보낸다() throws Exception {
        when(customerReturnService.request(eq(10L), eq(1L), eq("단순 변심"), anyList()))
                .thenThrow(new IllegalArgumentException("반품 가능 수량을 초과했습니다."));

        mockMvc.perform(post("/orders/10/returns")
                        .with(user(userPrincipal()))
                        .with(csrf())
                        .param("reason", "단순 변심")
                        .param("lines[0].orderItemId", "101")
                        .param("lines[0].quantity", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders/10"))
                .andExpect(flash().attributeExists("returnForm"))
                .andExpect(flash().attributeExists("org.springframework.validation.BindingResult.returnForm"));

        verify(returnSubmissionService, never()).submit(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 본인_반품_상세는_상태와_품목결과를_렌더링한다() throws Exception {
        CustomerReturn customerReturn = completedReturn();
        when(customerReturnService.findOwned(77L, 1L)).thenReturn(customerReturn);

        mockMvc.perform(get("/returns/77").with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(view().name("returnview"))
                .andExpect(content().string(containsString("반품 완료")))
                .andExpect(content().string(containsString("테스트상품")))
                .andExpect(content().string(containsString("재입고")))
                .andExpect(content().string(containsString("단순 변심")))
                .andExpect(content().string(containsString("/orders/10")));
    }

    @Test
    void 타인의_반품_상세는_404다() throws Exception {
        when(customerReturnService.findOwned(77L, 1L))
                .thenThrow(new EntityNotFoundException("Order", 10L));

        mockMvc.perform(get("/returns/77").with(user(userPrincipal())))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    @Test
    void CSRF가_없는_반품요청은_403이다() throws Exception {
        mockMvc.perform(post("/orders/10/returns")
                        .with(user(userPrincipal()))
                        .param("reason", "단순 변심"))
                .andExpect(status().isForbidden());

        verify(customerReturnService, never()).request(eq(10L), eq(1L), eq("단순 변심"), anyList());
    }

    @Test
    void 관리자는_고객_반품상세를_볼_수_없다() throws Exception {
        mockMvc.perform(get("/returns/77").with(user(adminPrincipal())))
                .andExpect(status().isForbidden());

        verify(customerReturnService, never()).findOwned(77L, 99L);
    }

    private CustomerReturn completedReturn() {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        ReflectionTestUtils.setField(member, "id", 1L);
        Product product = new Product();
        product.setId(501L);
        product.setName("테스트상품");
        product.setPrice(10000);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        OrderItem orderItem = OrderItem.createOrderItem(product, 10000, 2);
        ReflectionTestUtils.setField(orderItem, "id", 101L);
        Order order = Order.createOrder(member, delivery, orderItem);
        ReflectionTestUtils.setField(order, "id", 10L);
        order.ship();
        order.deliver();
        CustomerReturn customerReturn = CustomerReturn.create(order, UUID.randomUUID(), "단순 변심",
                List.of(new CustomerReturn.RequestItem(orderItem, 2)));
        ReflectionTestUtils.setField(customerReturn, "id", 77L);
        customerReturn.markRequested(900L);
        customerReturn.complete(List.of(new CustomerReturn.ResultItem(101L, 2,
                com.jhg.hgpage.oms.domain.enums.ReturnDisposition.RESTOCKED)));
        return customerReturn;
    }
}
