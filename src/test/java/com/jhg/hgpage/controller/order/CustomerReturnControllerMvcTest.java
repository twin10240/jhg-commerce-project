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
import com.jhg.hgpage.oms.web.controller.CustomerReturnController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
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

    private UserPrincipal userPrincipal() {
        return new UserPrincipal(1L, "user@example.com", "테스터", "010-0000-0000", "password", Role.USER);
    }

    private UserPrincipal adminPrincipal() {
        return new UserPrincipal(99L, "admin@example.com", "관리자", "010-1111-1111", "password", Role.ADMIN);
    }

    @Test
    void 양수인_품목만_요청하고_OMS_승인대기를_안내한다() throws Exception {
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
                .andExpect(flash().attribute("successMessage", "반품 신청이 접수되어 관리자 승인을 기다리고 있습니다."));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CustomerReturnService.ReturnLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(customerReturnService).request(eq(10L), eq(1L), eq("사이즈가 맞지 않습니다."), lines.capture());
        assertThat(lines.getValue()).containsExactly(new CustomerReturnService.ReturnLine(102L, 2));
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
                .andExpect(content().string(containsString("aria-label=\"반품 진행 상태\"")))
                .andExpect(content().string(containsString("<div class=\"timeline-step done\">반품 완료</div>")))
                .andExpect(content().string(containsString("단순 변심")))
                .andExpect(content().string(containsString("/orders/10")));
    }

    @Test
    void 승인대기_반품은_OMS_승인단계를_현재로_표시한다() throws Exception {
        when(customerReturnService.findOwned(77L, 1L)).thenReturn(approvalPendingReturn());

        mockMvc.perform(get("/returns/77").with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("OMS 승인 대기")))
                .andExpect(content().string(containsString("<div class=\"timeline-step current\">OMS 승인</div>")));
    }

    @Test
    void 반려된_반품은_반려사유를_표시한다() throws Exception {
        CustomerReturn value = approvalPendingReturn();
        value.reject("admin@example.com", "배송 완료 후 30일이 지났습니다.");
        when(customerReturnService.findOwned(77L, 1L)).thenReturn(value);

        mockMvc.perform(get("/returns/77").with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("반품 반려")))
                .andExpect(content().string(containsString("배송 완료 후 30일이 지났습니다.")));
    }

    @Test
    void WMS_전송대기_반품은_고객용_4단계와_현재단계를_표시한다() throws Exception {
        CustomerReturn customerReturn = pendingSubmissionReturn();
        when(customerReturnService.findOwned(77L, 1L)).thenReturn(customerReturn);

        mockMvc.perform(get("/returns/77").with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<div class=\"timeline-step done\">OMS 승인</div>")))
                .andExpect(content().string(containsString("<div class=\"timeline-step current\">WMS 접수</div>")))
                .andExpect(content().string(containsString(">창고 도착</div>")))
                .andExpect(content().string(containsString(">반품 완료</div>")));
    }

    @Test
    void 취소된_반품의_품목결과는_처리중이_아니라_취소로_표시한다() throws Exception {
        CustomerReturn customerReturn = requestedReturn();
        customerReturn.cancel();
        when(customerReturnService.findOwned(77L, 1L)).thenReturn(customerReturn);

        mockMvc.perform(get("/returns/77").with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("반품 취소")))
                .andExpect(content().string(containsString("<div class=\"timeline-step stopped\">WMS 접수</div>")))
                .andExpect(content().string(containsString("<td>취소</td>")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<td>처리 중</td>"))));
    }

    @Test
    void 접수실패한_반품의_품목결과는_처리중이_아니라_접수실패로_표시한다() throws Exception {
        CustomerReturn customerReturn = pendingSubmissionReturn();
        customerReturn.failSubmission("BAD_REQUEST");
        when(customerReturnService.findOwned(77L, 1L)).thenReturn(customerReturn);

        mockMvc.perform(get("/returns/77").with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<div class=\"timeline-step stopped\">WMS 접수</div>")))
                .andExpect(content().string(containsString("접수 실패 사유")))
                .andExpect(content().string(containsString("반품 요청 정보가 올바르지 않거나 반품 가능 수량을 초과했습니다.")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("BAD_REQUEST"))))
                .andExpect(content().string(containsString("<td>접수 실패</td>")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<td>처리 중</td>"))));
    }

    @ParameterizedTest
    @CsvSource({
            "CONFLICT,이미 처리된 반품 요청과 충돌했습니다.",
            "UNKNOWN,WMS에서 반품 요청을 처리할 수 없습니다."
    })
    void 접수실패_코드를_고객용_사유로_변환한다(String code, String expectedReason) throws Exception {
        CustomerReturn customerReturn = pendingSubmissionReturn();
        customerReturn.failSubmission(code);
        when(customerReturnService.findOwned(77L, 1L)).thenReturn(customerReturn);

        mockMvc.perform(get("/returns/77").with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(expectedReason)))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(code))));
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
        CustomerReturn customerReturn = requestedReturn();
        customerReturn.complete(List.of(new CustomerReturn.ResultItem(101L, 2,
                com.jhg.hgpage.oms.domain.enums.ReturnDisposition.RESTOCKED)));
        return customerReturn;
    }

    private CustomerReturn requestedReturn() {
        CustomerReturn customerReturn = pendingSubmissionReturn();
        customerReturn.markRequested(900L);
        return customerReturn;
    }

    private CustomerReturn pendingSubmissionReturn() {
        CustomerReturn customerReturn = approvalPendingReturn();
        customerReturn.approve("admin@example.com");
        return customerReturn;
    }

    private CustomerReturn approvalPendingReturn() {
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
        return customerReturn;
    }
}
