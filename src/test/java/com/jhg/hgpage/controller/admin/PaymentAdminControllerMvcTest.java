package com.jhg.hgpage.controller.admin;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.oms.domain.enums.PaymentStatus;
import com.jhg.hgpage.oms.domain.enums.RefundStatus;
import com.jhg.hgpage.oms.service.PaymentAdminService;
import com.jhg.hgpage.oms.service.PaymentAdminService.PageView;
import com.jhg.hgpage.oms.service.PaymentAdminService.ReviewCounts;
import com.jhg.hgpage.oms.web.controller.PaymentAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(PaymentAdminController.class)
@Import(SecurityConfig.class)
class PaymentAdminControllerMvcTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean PaymentAdminService paymentAdminService;

    private UserPrincipal admin() {
        return new UserPrincipal(2L, "admin@admin.com", "관리자", "010-1111-2222", "pw", Role.ADMIN);
    }

    private UserPrincipal normalUser() {
        return new UserPrincipal(1L, "user@example.com", "사용자", "010-0000-0000", "pw", Role.USER);
    }

    @Test
    void adminFiltersAndRendersPaymentReviewCounts() throws Exception {
        var page = new PageView(List.of(), List.of(), new ReviewCounts(1, 2, 3, 4));
        when(paymentAdminService.findPage(PaymentStatus.PAYMENT_REVIEW, RefundStatus.MANUAL_REVIEW))
                .thenReturn(page);

        mockMvc.perform(get("/admin/payments")
                        .param("paymentStatus", "PAYMENT_REVIEW")
                        .param("refundStatus", "MANUAL_REVIEW")
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/payments"))
                .andExpect(model().attribute("payments", page.payments()))
                .andExpect(model().attribute("refunds", page.refunds()))
                .andExpect(model().attribute("counts", page.counts()))
                .andExpect(model().attribute("paymentStatus", PaymentStatus.PAYMENT_REVIEW))
                .andExpect(model().attribute("refundStatus", RefundStatus.MANUAL_REVIEW))
                .andExpect(content().string(containsString("환불 수동 확인")))
                .andExpect(content().string(containsString("1건")))
                .andExpect(content().string(containsString("재고 할당 수동 확인")))
                .andExpect(content().string(containsString("2건")))
                .andExpect(content().string(containsString("결제 취소 수동 확인")))
                .andExpect(content().string(containsString("3건")))
                .andExpect(content().string(containsString("재고 취소 검토")))
                .andExpect(content().string(containsString("4건")));

        verify(paymentAdminService).findPage(PaymentStatus.PAYMENT_REVIEW, RefundStatus.MANUAL_REVIEW);
    }

    @Test
    void userCannotOpenPaymentAdministration() throws Exception {
        mockMvc.perform(get("/admin/payments").with(user(normalUser())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentAdminService);
    }

    @Test
    void adminRetriesRefundWithCsrf() throws Exception {
        mockMvc.perform(post("/admin/refunds/7/retry").with(user(admin())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/payments"));

        verify(paymentAdminService).retryRefund(7L);
    }

    @Test
    void userCannotRetryRefund() throws Exception {
        mockMvc.perform(post("/admin/refunds/7/retry").with(user(normalUser())).with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentAdminService);
    }

    @Test
    void refundRetryRequiresCsrf() throws Exception {
        mockMvc.perform(post("/admin/refunds/7/retry").with(user(admin())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentAdminService);
    }

    @Test
    void adminRetriesCancellationPaymentWithCsrf() throws Exception {
        mockMvc.perform(post("/admin/payment-attempts/9/retry").with(user(admin())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/payments"));

        verify(paymentAdminService).retryCancellationPayment(9L);
    }

    @Test
    void userCannotRetryCancellationPayment() throws Exception {
        mockMvc.perform(post("/admin/payment-attempts/9/retry").with(user(normalUser())).with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentAdminService);
    }

    @Test
    void cancellationPaymentRetryRequiresCsrf() throws Exception {
        mockMvc.perform(post("/admin/payment-attempts/9/retry").with(user(admin())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentAdminService);
    }
}
