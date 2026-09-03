package com.jhg.hgpage.realtime.web;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.oms.service.AccountService;
import com.jhg.hgpage.oms.web.controller.AuthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest({NotificationPageController.class, RealtimeViewAdvice.class, AuthController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = "realtime.public-url=https://realtime.example:3000///")
class NotificationPageControllerMvcTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AccountService accountService;
    @MockitoBean PasswordEncoder passwordEncoder;

    @Test
    void anonymous_is_redirected_to_login() throws Exception {
        mockMvc.perform(get("/notifications"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    void user_gets_the_notification_view_and_normalized_public_url() throws Exception {
        mockMvc.perform(get("/notifications").with(user(normalUser())))
                .andExpect(status().isOk())
                .andExpect(view().name("notifications"))
                .andExpect(model().attribute("realtimePublicUrl", "https://realtime.example:3000"))
                .andExpect(content().string(containsString("<h1 class=\"app-title\">알림</h1>")))
                .andExpect(content().string(containsString("aria-live=\"polite\"")))
                .andExpect(content().string(containsString("모두 읽음")))
                .andExpect(content().string(containsString("다시 시도")))
                .andExpect(content().string(not(containsString("배송이 완료되었습니다."))));
    }

    @Test
    void admin_cannot_view_customer_notifications_or_receive_the_public_url() throws Exception {
        mockMvc.perform(get("/notifications").with(user(admin())))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/login").with(user(admin())))
                .andExpect(model().attributeDoesNotExist("realtimePublicUrl"));
    }

    @Test
    void anonymous_login_and_signup_models_do_not_receive_the_public_url() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("realtimePublicUrl"));
        mockMvc.perform(get("/signup"))
                .andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("realtimePublicUrl"));
    }

    private UserPrincipal normalUser() {
        return new UserPrincipal(1L, "user@example.com", "사용자", "010-0000-0000", "pw", Role.USER);
    }

    private UserPrincipal admin() {
        return new UserPrincipal(2L, "admin@example.com", "관리자", "010-1111-2222", "pw", Role.ADMIN);
    }
}
