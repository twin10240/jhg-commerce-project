package com.jhg.hgpage.realtime.chat;

import com.jhg.hgpage.config.SecurityConfig;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.realtime.chat.dto.ChatDtos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatBffController.class)
@Import({SecurityConfig.class, ChatBffService.class})
@TestPropertySource(properties = "realtime.chat.enabled=true")
class ChatBffControllerMvcTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean OrderRepository orders;
    @MockitoBean ChatDeliveryClient client;

    @Test void 본인_주문만_상담방을_연다() throws Exception {
        when(orders.findDetailById(10L)).thenReturn(Optional.of(order(1L)));
        when(client.createConversation("10", 1L)).thenReturn(conversation());

        mockMvc.perform(post("/api/chat/conversations").with(user(principal(1L))).with(csrf()).contentType("application/json").content("{\"orderId\":10}"))
                .andExpect(status().isOk());
        verify(client).createConversation("10", 1L);
    }

    @Test void 다른_고객의_주문은_존재하지_않는것처럼_처리한다() throws Exception {
        when(orders.findDetailById(10L)).thenReturn(Optional.of(order(2L)));

        mockMvc.perform(post("/api/chat/conversations").with(user(principal(1L))).with(csrf()).contentType("application/json").content("{\"orderId\":10}"))
                .andExpect(status().isNotFound());
    }

    @Test @WithMockUser(roles = "USER") void csrf_없이_생성할수_없다() throws Exception {
        mockMvc.perform(post("/api/chat/conversations").contentType("application/json").content("{\"orderId\":10}"))
                .andExpect(status().isForbidden());
    }

    private UserPrincipal principal(long id) { return new UserPrincipal(id, "u@test", "user", "010", "pw", Role.USER); }
    private Order order(long memberId) {
        Member member = Member.createAdmin("owner", "010", null);
        org.springframework.test.util.ReflectionTestUtils.setField(member, "id", memberId);
        return Order.createOrder(member, new com.jhg.hgpage.oms.domain.Delivery());
    }
    private ChatDtos.Conversation conversation() { return new ChatDtos.Conversation(UUID.randomUUID(), "10", "1", "OPEN", null, Instant.now(), Instant.now()); }
}
