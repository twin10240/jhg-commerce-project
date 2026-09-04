package com.jhg.hgpage.realtime.chat;

import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.realtime.chat.dto.ChatDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat/conversations")
@Validated
@ConditionalOnProperty(name = "realtime.chat.enabled", havingValue = "true")
public class ChatBffController {
    private final ChatBffService service;
    public ChatBffController(ChatBffService service) { this.service = service; }

    @PostMapping public ChatDtos.Conversation create(@AuthenticationPrincipal UserPrincipal p, @Valid @RequestBody ChatDtos.CreateConversationRequest r) { return service.createConversation(p, r.orderId()); }
    @GetMapping public List<ChatDtos.Conversation> list(@AuthenticationPrincipal UserPrincipal p) { return service.conversations(p); }
    @GetMapping("/{id}/messages") public List<ChatDtos.Message> messages(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) { return service.messages(p, id, cursor, limit); }
    @PostMapping("/{id}/messages") public ChatDtos.Message send(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id, @Valid @RequestBody ChatDtos.SendMessageRequest r) { return service.send(p, id, r); }
    @PostMapping("/{id}/read") public ChatDtos.ReadResult read(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id) { return service.read(p, id); }
    @PatchMapping("/{id}") public ChatDtos.Conversation status(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id, @Valid @RequestBody ChatDtos.UpdateStatusRequest r) { return service.status(p, id, r.status()); }
}
