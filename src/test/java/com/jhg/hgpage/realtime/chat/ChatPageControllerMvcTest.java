package com.jhg.hgpage.realtime.chat;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.oms.service.AccountService;
import com.jhg.hgpage.oms.web.controller.AuthController;
import com.jhg.hgpage.realtime.web.RealtimeViewAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest({ChatPageController.class, RealtimeViewAdvice.class, AuthController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"realtime.chat.enabled=true", "realtime.public-url=https://realtime.example:3000"})
class ChatPageControllerMvcTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean AccountService accountService;
    @MockitoBean PasswordEncoder passwordEncoder;

    @Test
    void customer_chat_shell_has_stable_contract_and_order_link() throws Exception {
        mockMvc.perform(get("/chat").param("orderId", "101").with(user(customer())))
                .andExpect(status().isOk())
                .andExpect(view().name("chat"))
                .andExpect(model().attribute("memberId", 1L))
                .andExpect(content().string(containsString("data-chat-root")))
                .andExpect(content().string(containsString("data-member-id=\"1\"")))
                .andExpect(content().string(containsString("data-chat-conversation-id")))
                .andExpect(content().string(containsString("data-chat-messages")))
                .andExpect(content().string(containsString("data-chat-message-form")))
                .andExpect(content().string(containsString("data-chat-body")))
                .andExpect(content().string(containsString("data-chat-load-more")));
    }

    @Test
    void admin_chat_shell_has_stable_contract() throws Exception {
        mockMvc.perform(get("/admin/chat").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/chat"))
                .andExpect(model().attribute("memberId", 2L))
                .andExpect(content().string(containsString("data-chat-root")))
                .andExpect(content().string(containsString("data-member-id=\"2\"")))
                .andExpect(content().string(containsString("data-chat-conversation-id")))
                .andExpect(content().string(containsString("data-chat-messages")))
                .andExpect(content().string(containsString("data-chat-message-form")))
                .andExpect(content().string(containsString("data-chat-body")))
                .andExpect(content().string(containsString("data-chat-load-more")));
    }

    @Test
    void roles_cannot_open_the_other_chat_page() throws Exception {
        mockMvc.perform(get("/chat").param("orderId", "101").with(user(admin())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/chat").with(user(customer())))
                .andExpect(status().isForbidden());
    }

    @Test
    void notification_link_redirects_each_role_to_its_chat_page() throws Exception {
        String id = "7ee1c992-4b85-4bb3-8f1c-8f8a6d57bc34";
        mockMvc.perform(get("/chat/conversations/{id}", id).param("orderId", "101").with(user(customer())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chat?orderId=101&conversationId=" + id));
        mockMvc.perform(get("/chat/conversations/{id}", id).param("orderId", "101").with(user(admin())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/chat?conversationId=" + id));
    }

    @Test
    void order_detail_keeps_the_customer_consultation_link() throws Exception {
        String template = new String(new ClassPathResource("templates/orderview.html").getInputStream().readAllBytes());
        assertTrue(template.contains("@{/chat(orderId=${order.id})}"));
        String client = new String(new ClassPathResource("static/js/chat-client.js").getInputStream().readAllBytes());
        assertTrue(client.contains("update.readerMemberId"));
    }

    private UserPrincipal customer() { return new UserPrincipal(1L, "user@example.com", "사용자", "010", "pw", Role.USER); }
    private UserPrincipal admin() { return new UserPrincipal(2L, "admin@example.com", "관리자", "010", "pw", Role.ADMIN); }
}
