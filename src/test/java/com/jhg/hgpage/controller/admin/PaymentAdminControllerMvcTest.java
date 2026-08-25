package com.jhg.hgpage.controller.admin;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.oms.domain.enums.PaymentStatus;
import com.jhg.hgpage.oms.domain.enums.RefundStatus;
import com.jhg.hgpage.oms.dto.AdminPaymentDto;
import com.jhg.hgpage.oms.dto.AdminPaymentDto.WorkType;
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
import static org.hamcrest.Matchers.not;
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
        when(paymentAdminService.findPage(false, PaymentStatus.PAYMENT_REVIEW, null))
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
                .andExpect(model().attribute("refundStatus", org.hamcrest.Matchers.nullValue()))
                .andExpect(content().string(containsString("환불 수동 확인")))
                .andExpect(content().string(containsString("1건")))
                .andExpect(content().string(containsString("재고 할당 수동 확인")))
                .andExpect(content().string(containsString("2건")))
                .andExpect(content().string(containsString("결제 취소 수동 확인")))
                .andExpect(content().string(containsString("3건")))
                .andExpect(content().string(containsString("재고 취소 검토")))
                .andExpect(content().string(containsString("4건")));

        verify(paymentAdminService).findPage(false, PaymentStatus.PAYMENT_REVIEW, null);
    }

    @Test
    void 환불탭은_환불필터와_환불작업만_보여준다() throws Exception {
        when(paymentAdminService.findPage(true, null, RefundStatus.MANUAL_REVIEW)).thenReturn(
                new PageView(List.of(), List.of(), new ReviewCounts(0, 0, 0, 0)));

        mockMvc.perform(get("/admin/payments")
                        .param("tab", "refund")
                        .param("refundStatus", "MANUAL_REVIEW")
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"refundStatus\"")))
                .andExpect(content().string(containsString("id=\"refund-table-title\"")))
                .andExpect(content().string(not(containsString("name=\"paymentStatus\""))))
                .andExpect(content().string(not(containsString("id=\"payment-table-title\""))));
    }

    @Test
    void admin은_완료된_환불의_게이트웨이_거래번호를_확인한다() throws Exception {
        var refund = new AdminPaymentDto(WorkType.REFUND, 7L, 10L, null,
                "SUCCEEDED", "환불 완료", 10_000, 10_000, 0, 10_000,
                "refund-request-key", "MOCK-REFUND-1", 1, null, null, false);
        when(paymentAdminService.findPage(true, null, null)).thenReturn(
                new PageView(List.of(), List.of(refund), new ReviewCounts(0, 0, 0, 0)));

        mockMvc.perform(get("/admin/payments").param("tab", "refund").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("MOCK-REFUND-1")));
    }

    @Test
    void admin은_모든_금액의_0을_0원으로_확인한다() throws Exception {
        var payment = new AdminPaymentDto(WorkType.PAYMENT, 1L, 10L, null,
                "PENDING", "결제 대기", 0, 0, 0, 0,
                null, null, 0, null, null, false);
        var refund = new AdminPaymentDto(WorkType.REFUND, 2L, 10L, 20L,
                "PENDING", "환불 대기", 0, 0, 0, 0,
                "refund-request-key", null, 0, null, null, false);
        when(paymentAdminService.findPage(false, null, null)).thenReturn(
                new PageView(List.of(payment), List.of(), new ReviewCounts(0, 0, 0, 0)));
        when(paymentAdminService.findPage(true, null, null)).thenReturn(
                new PageView(List.of(), List.of(refund), new ReviewCounts(0, 0, 0, 0)));

        mockMvc.perform(get("/admin/payments").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">0원</td>")))
                .andExpect(content().string(not(containsString(">000원</td>"))));

        mockMvc.perform(get("/admin/payments").param("tab", "refund").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">0원</td>")))
                .andExpect(content().string(not(containsString(">000원</td>"))));
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
                .andExpect(redirectedUrl("/admin/payments?tab=refund"));

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
                .andExpect(redirectedUrl("/admin/payments?tab=payment"));

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
