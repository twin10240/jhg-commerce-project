package com.jhg.hgpage.realtime.chat;

import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.realtime.chat.dto.ChatDtos;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "realtime.chat.enabled", havingValue = "true")
public class ChatBffService {
    private final OrderRepository orders;
    private final ChatDeliveryClient chat;

    public ChatBffService(OrderRepository orders, ChatDeliveryClient chat) { this.orders = orders; this.chat = chat; }

    public ChatDtos.Conversation createConversation(UserPrincipal principal, Long orderId) {
        if (principal.getRole() != Role.USER) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        Order order = orders.findDetailById(orderId).orElseThrow(() -> new EntityNotFoundException("Order", orderId));
        if (!order.getMember().getId().equals(principal.getId())) throw new EntityNotFoundException("Order", orderId);
        return chat.createConversation(orderId.toString(), principal.getId());
    }
    public List<ChatDtos.Conversation> conversations(UserPrincipal p) { return chat.listConversations(p.getId(), p.getRole()); }
    public List<ChatDtos.Message> messages(UserPrincipal p, UUID id, String cursor, int limit) { return chat.listMessages(id, p.getId(), p.getRole(), cursor, limit); }
    public ChatDtos.Message send(UserPrincipal p, UUID id, ChatDtos.SendMessageRequest request) { return chat.sendMessage(id, p.getId(), p.getRole(), request.body(), request.clientMessageId()); }
    public ChatDtos.ReadResult read(UserPrincipal p, UUID id) { return chat.markRead(id, p.getId(), p.getRole()); }
    public ChatDtos.Conversation status(UserPrincipal p, UUID id, String status) { return chat.updateStatus(id, p.getId(), p.getRole(), status); }
}
