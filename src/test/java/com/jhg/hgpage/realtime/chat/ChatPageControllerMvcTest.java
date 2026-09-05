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

    @Test
    void chat_messages_align_sent_left_and_received_right() throws Exception {
        String css = new String(new ClassPathResource("static/css/chat.css").getInputStream().readAllBytes());
        assertTrue(css.contains(".chat-message:not(.mine){align-self:flex-end}"));
        assertTrue(css.contains(".chat-message.mine{background:#fde4d3;align-self:flex-start}"));
    }

    @Test
    void chat_page_uses_the_shared_app_background() throws Exception {
        String css = new String(new ClassPathResource("static/css/chat.css").getInputStream().readAllBytes());
        assertTrue(css.contains("body{background:var(--app-bg)}"));
    }

    @Test
    void chat_page_text_uses_the_shared_ink_color() throws Exception {
        String css = new String(new ClassPathResource("static/css/chat.css").getInputStream().readAllBytes());
        assertTrue(css.contains(".chat-page{max-width:960px;margin:24px auto;padding:0 16px;color:var(--app-ink)}"));
        assertTrue(css.contains(".chat-page h1{margin-bottom:4px;color:var(--app-ink)}"));
    }

    @Test
    void chat_messages_keep_dark_text_on_light_bubbles() throws Exception {
        String css = new String(new ClassPathResource("static/css/chat.css").getInputStream().readAllBytes());
        assertTrue(css.contains(".chat-message{max-width:78%;padding:10px 12px;border-radius:12px;background:#f7efe7;color:#1f1b18;"));
    }

    @Test
    void customer_chat_creates_a_conversation_only_when_sending() throws Exception {
        String client = new String(new ClassPathResource("static/js/chat-client.js").getInputStream().readAllBytes());
        assertTrue(client.contains("async function loadCustomerConversation()"));
        assertTrue(client.contains("if (!conversation) { conversation = await api('', { method: 'POST'"));
        assertTrue(client.contains("panel.dataset.chatConversationId = conversation.id; setStatus(`주문 #${conversation.orderId} 상담 (진행 중)`);"));
        assertTrue(client.contains("else if (role === 'USER') await loadCustomerConversation();"));
    }

    @Test
    void closed_chat_disables_the_message_composer() throws Exception {
        String client = new String(new ClassPathResource("static/js/chat-client.js").getInputStream().readAllBytes());
        assertTrue(client.contains("body.disabled = !enabled"));
        assertTrue(client.contains("send.disabled = !enabled"));
        assertTrue(client.contains("setComposerState(next.status === 'OPEN')"));
    }

    private UserPrincipal customer() { return new UserPrincipal(1L, "user@example.com", "사용자", "010", "pw", Role.USER); }
    private UserPrincipal admin() { return new UserPrincipal(2L, "admin@example.com", "관리자", "010", "pw", Role.ADMIN); }
}
