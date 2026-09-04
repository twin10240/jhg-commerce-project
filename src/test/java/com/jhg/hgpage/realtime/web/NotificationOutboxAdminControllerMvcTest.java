package com.jhg.hgpage.realtime.web;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.realtime.outbox.NotificationOutboxService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

@WebMvcTest(NotificationOutboxAdminController.class)
@Import(SecurityConfig.class)
class NotificationOutboxAdminControllerMvcTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean NotificationOutboxService notificationOutboxService;

    @Test
    void anonymous_is_redirected_to_login() throws Exception {
        mockMvc.perform(get("/admin/notification-events"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));

        verifyNoInteractions(notificationOutboxService);
    }

    @Test
    void user_cannot_view_or_retry_failed_events() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(get("/admin/notification-events").with(user(normalUser())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/notification-events/{id}/retry", id).with(user(normalUser())).with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(notificationOutboxService);
    }

    @Test
    void admin_sees_only_safe_failed_event_fields_and_navigation() throws Exception {
        UUID id = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var failedEvents = List.of(new NotificationOutboxService.FailedEvent(
                id, eventId, "DELIVERY_COMPLETED", "ORDER", "42", 3, "HTTP_422",
                Instant.parse("2026-08-30T06:30:00Z")));
        when(notificationOutboxService.findFailed()).thenReturn(failedEvents);

        mockMvc.perform(get("/admin/notification-events").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/notification-events"))
                .andExpect(model().attribute("failedEvents", failedEvents))
                .andExpect(content().string(containsString(eventId.toString())))
                .andExpect(content().string(containsString("배송 완료")))
                .andExpect(content().string(not(containsString("DELIVERY_COMPLETED"))))
                .andExpect(content().string(containsString("ORDER #42")))
                .andExpect(content().string(containsString("요청 처리 오류")))
                .andExpect(content().string(not(containsString("HTTP_422"))))
                .andExpect(content().string(containsString("/admin/notification-events")))
                .andExpect(content().string(not(containsString("recipient@example.com"))))
                .andExpect(content().string(not(containsString("sensitive-payload"))))
                .andExpect(content().string(containsString("알림 이벤트")));

        verify(notificationOutboxService).findFailed();
    }

    @Test
    void empty_failed_event_list_has_a_korean_empty_state() throws Exception {
        when(notificationOutboxService.findFailed()).thenReturn(List.of());

        mockMvc.perform(get("/admin/notification-events").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("재전송이 필요한 알림 이벤트가 없습니다.")));
    }

    @Test
    void admin_sees_the_chat_message_label() throws Exception {
        var failedEvents = List.of(new NotificationOutboxService.FailedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "CHAT_MESSAGE", "ORDER", "42", 3, "HTTP_422",
                Instant.parse("2026-08-30T06:30:00Z")));
        when(notificationOutboxService.findFailed()).thenReturn(failedEvents);

        mockMvc.perform(get("/admin/notification-events").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("새 상담 메시지")));
    }

    @Test
    void admin_can_retry_a_failed_event() throws Exception {
        UUID id = UUID.randomUUID();
        when(notificationOutboxService.requeueFailed(eq(id), any(Instant.class))).thenReturn(true);

        mockMvc.perform(post("/admin/notification-events/{id}/retry", id).with(user(admin())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/notification-events"))
                .andExpect(flash().attribute("successMessage", "알림 이벤트를 재전송 대기 상태로 변경했습니다."));

        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        verify(notificationOutboxService).requeueFailed(eq(id), now.capture());
    }

    @Test
    void missing_or_nonfailed_event_returns_a_generic_error() throws Exception {
        UUID id = UUID.randomUUID();
        when(notificationOutboxService.requeueFailed(eq(id), any(Instant.class))).thenReturn(false);

        mockMvc.perform(post("/admin/notification-events/{id}/retry", id).with(user(admin())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/notification-events"))
                .andExpect(flash().attribute("errorMessage", "재전송할 수 없는 알림 이벤트입니다."));
    }

    @Test
    void malformed_event_id_returns_a_clear_error_without_calling_service() throws Exception {
        mockMvc.perform(post("/admin/notification-events/{id}/retry", "not-a-uuid").with(user(admin())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/notification-events"))
                .andExpect(flash().attribute("errorMessage", "알림 이벤트 ID가 올바르지 않습니다."));

        verify(notificationOutboxService, never()).requeueFailed(any(), any());
    }

    @Test
    void retry_requires_csrf() throws Exception {
        mockMvc.perform(post("/admin/notification-events/{id}/retry", UUID.randomUUID()).with(user(admin())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(notificationOutboxService);
    }

    private UserPrincipal admin() {
        return new UserPrincipal(2L, "admin@example.com", "관리자", "010-1111-2222", "pw", Role.ADMIN);
    }

    private UserPrincipal normalUser() {
        return new UserPrincipal(1L, "user@example.com", "사용자", "010-0000-0000", "pw", Role.USER);
    }
}
