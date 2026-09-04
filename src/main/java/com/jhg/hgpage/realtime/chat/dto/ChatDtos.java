package com.jhg.hgpage.realtime.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class ChatDtos {
    private ChatDtos() {}

    public record CreateConversationRequest(@NotNull Long orderId) {}
    public record SendMessageRequest(@NotBlank @Size(max = 2000) String body, @NotNull UUID clientMessageId) {}
    public record UpdateStatusRequest(@NotBlank String status) {}
    public record Conversation(UUID id, String orderId, String customerMemberId, String status,
                               Instant lastMessageAt, Instant createdAt, Instant updatedAt) {}
    public record Message(UUID id, UUID conversationId, String senderMemberId, String senderRole,
                          String body, UUID clientMessageId, Instant createdAt, Instant readAt) {}
    public record ReadResult(int changedCount) {}
}
