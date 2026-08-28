package com.jhg.hgpage.controller.admin;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.dto.AdminCustomerReturnDto;
import com.jhg.hgpage.oms.service.CustomerReturnService;
import com.jhg.hgpage.oms.service.ReturnSubmissionService;
import com.jhg.hgpage.oms.web.controller.CustomerReturnAdminController;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(CustomerReturnAdminController.class)
@Import(SecurityConfig.class)
class CustomerReturnAdminControllerMvcTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean CustomerReturnService customerReturnService;
    @MockitoBean ReturnSubmissionService returnSubmissionService;

    private UserPrincipal admin() {
        return new UserPrincipal(2L, "admin@example.com", "관리자", "010-1111-2222", "pw", Role.ADMIN);
    }

    private UserPrincipal normalUser() {
        return new UserPrincipal(1L, "user@example.com", "사용자", "010-0000-0000", "pw", Role.USER);
    }

    @Test
    void 관리자는_승인대기_반품과_처리버튼을_본다() throws Exception {
        when(customerReturnService.findAllForAdmin(CustomerReturnStatus.PENDING_APPROVAL))
                .thenReturn(List.of(pendingRow()));

        mockMvc.perform(get("/admin/returns")
                        .param("status", "PENDING_APPROVAL").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/returns"))
                .andExpect(model().attribute("activeStatus", CustomerReturnStatus.PENDING_APPROVAL))
                .andExpect(content().string(containsString("OMS 승인 대기")))
                .andExpect(content().string(containsString("<th class=\"action-column\">처리</th>")))
                .andExpect(content().string(containsString("<td class=\"action-column\"><div class=\"row-actions\"")))
                .andExpect(content().string(containsString("/admin/returns/77/approve")))
                .andExpect(content().string(containsString("/admin/returns/77/reject")))
                .andExpect(content().string(not(containsString("좌우로 밀어 상세 정보를 확인하세요"))))
                .andExpect(content().string(not(containsString("data-scroll-direction"))));
    }

    @Test
    void 반려_사유는_처리칸이_아닌_모달에서_입력한다() throws Exception {
        when(customerReturnService.findAllForAdmin(null)).thenReturn(List.of(pendingRow()));

        mockMvc.perform(get("/admin/returns").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">\uC2B9\uC778</button>")))
                .andExpect(content().string(containsString(">\uBC18\uB824</button>")))
                .andExpect(content().string(not(containsString("<input name=\"reason\""))))
                .andExpect(content().string(containsString(
                        "data-reject-url=\"/admin/returns/77/reject\"")))
                .andExpect(content().string(containsString("<dialog id=\"reject-dialog\"")))
                .andExpect(content().string(containsString(
                        "<textarea id=\"reject-reason\" name=\"reason\" maxlength=\"500\" required")))
                .andExpect(content().string(containsString(">\uBC18\uB824 \uD655\uC778</button>")));
    }

    @Test
    void 승인하면_상태를_먼저_바꾸고_WMS에_전송한다() throws Exception {
        mockMvc.perform(post("/admin/returns/77/approve").with(user(admin())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/returns"))
                .andExpect(flash().attribute("successMessage", "반품을 승인했습니다."));

        InOrder calls = inOrder(customerReturnService, returnSubmissionService);
        calls.verify(customerReturnService).approveReturn(77L, "admin@example.com");
        calls.verify(returnSubmissionService).submit(77L);
    }

    @Test
    void 승인_후_예상밖_WMS오류는_재시도안내를_보인다() throws Exception {
        doThrow(new IllegalStateException("WMS unavailable")).when(returnSubmissionService).submit(77L);

        mockMvc.perform(post("/admin/returns/77/approve").with(user(admin())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("successMessage", "승인은 완료되었으며 WMS 전송을 다시 확인합니다."));

        verify(customerReturnService).approveReturn(77L, "admin@example.com");
    }

    @Test
    void 반려는_사유를_필수로_저장하고_WMS를_호출하지않는다() throws Exception {
        mockMvc.perform(post("/admin/returns/77/reject").with(user(admin())).with(csrf())
                        .param("reason", "정책상 반품 불가"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/returns"))
                .andExpect(flash().attribute("successMessage", "반품을 반려했습니다."));

        verify(customerReturnService).rejectReturn(77L, "admin@example.com", "정책상 반품 불가");
        verifyNoInteractions(returnSubmissionService);
    }

    @Test
    void 빈_반려사유는_오류를_보이고_저장하지않는다() throws Exception {
        doThrow(new IllegalArgumentException("반려 사유는 1자 이상 500자 이하여야 합니다."))
                .when(customerReturnService).rejectReturn(77L, "admin@example.com", " ");

        mockMvc.perform(post("/admin/returns/77/reject").with(user(admin())).with(csrf())
                        .param("reason", " "))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errorMessage", "반려 사유는 1자 이상 500자 이하여야 합니다."));

        verifyNoInteractions(returnSubmissionService);
    }

    @Test
    void 이미_검토한_반품은_오류를_보이고_WMS에_전송하지않는다() throws Exception {
        doThrow(new IllegalStateException("OMS 승인 대기 상태의 반품만 처리할 수 있습니다."))
                .when(customerReturnService).approveReturn(77L, "admin@example.com");

        mockMvc.perform(post("/admin/returns/77/approve").with(user(admin())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errorMessage", "OMS 승인 대기 상태의 반품만 처리할 수 있습니다."));

        verifyNoInteractions(returnSubmissionService);
    }

    @Test
    void 사용자에게는_모든_반품관리_경로가_금지된다() throws Exception {
        mockMvc.perform(get("/admin/returns").with(user(normalUser())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/returns/77/approve").with(user(normalUser())).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/returns/77/reject").with(user(normalUser())).with(csrf())
                        .param("reason", "반려"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(customerReturnService, returnSubmissionService);
    }

    @Test
    void 반품처리는_CSRF가_필수다() throws Exception {
        mockMvc.perform(post("/admin/returns/77/approve").with(user(admin())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/returns/77/reject").with(user(admin())).param("reason", "반려"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(customerReturnService, returnSubmissionService);
    }

    private AdminCustomerReturnDto pendingRow() {
        return new AdminCustomerReturnDto(77L, 10L, "테스터", CustomerReturnStatus.PENDING_APPROVAL,
                "OMS 승인 대기", "상품 불량", null, null, null, null,
                LocalDateTime.of(2026, 8, 26, 10, 30),
                List.of(new AdminCustomerReturnDto.Item("상품 1", 2)));
    }
}
