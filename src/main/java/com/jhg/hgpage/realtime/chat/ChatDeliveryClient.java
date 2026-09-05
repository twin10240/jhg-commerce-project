package com.jhg.hgpage.realtime.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.realtime.chat.dto.ChatDtos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "realtime.chat.enabled", havingValue = "true")
public class ChatDeliveryClient {
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final byte[] secret;

    public ChatDeliveryClient(RestClient.Builder builder, ObjectMapper objectMapper,
                              @Value("${realtime.base-url}") String baseUrl,
                              @Value("${realtime.chat-hmac-secret:}") String secret) {
        if (secret.isBlank()) throw new IllegalStateException("realtime.chat-hmac-secret must be configured");
        this.client = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public ChatDtos.Conversation createConversation(String orderId, Long customerMemberId) {
        return request("POST", "/internal/v1/chat/conversations", Map.of("orderId", orderId, "customerMemberId", customerMemberId.toString()), ChatDtos.Conversation.class);
    }

    public List<ChatDtos.Conversation> listConversations(Long memberId, Role role) {
        return requestList("GET", "/internal/v1/chat/conversations?memberId=" + memberId + "&role=" + role.name(), null, new TypeReference<>() {});
    }

    public List<ChatDtos.Message> listMessages(UUID conversationId, Long memberId, Role role, String cursor, int limit) {
        String uri = UriComponentsBuilder.fromPath("/internal/v1/chat/conversations/{id}/messages")
                .queryParam("memberId", memberId).queryParam("role", role.name()).queryParam("limit", limit)
                .queryParamIfPresent("cursor", java.util.Optional.ofNullable(cursor)).buildAndExpand(conversationId).toUriString();
        return requestList("GET", uri, null, new TypeReference<>() {});
    }

    public ChatDtos.Message sendMessage(UUID conversationId, Long senderMemberId, Role role, String body, UUID clientMessageId) {
        return request("POST", "/internal/v1/chat/conversations/" + conversationId + "/messages", Map.of(
                "senderMemberId", senderMemberId.toString(), "senderRole", role.name(), "body", body, "clientMessageId", clientMessageId.toString()), ChatDtos.Message.class);
    }

    public ChatDtos.ReadResult markRead(UUID conversationId, Long memberId, Role role) {
        return request("POST", "/internal/v1/chat/conversations/" + conversationId + "/read", Map.of("memberId", memberId.toString(), "role", role.name()), ChatDtos.ReadResult.class);
    }

    public ChatDtos.Conversation updateStatus(UUID conversationId, Long memberId, Role role, String status) {
        return request("PATCH", "/internal/v1/chat/conversations/" + conversationId, Map.of("memberId", memberId.toString(), "role", role.name(), "status", status), ChatDtos.Conversation.class);
    }

    private <T> T request(String method, String uri, Object body, Class<T> type) {
        String json = json(body);
        return read(method, uri, json, type, null);
    }

    private <T> List<T> requestList(String method, String uri, Object body, TypeReference<List<T>> type) {
        return read(method, uri, body == null ? "" : json(body), null, type);
    }

    private <T> T read(String method, String uri, String body, Class<T> type, TypeReference<?> listType) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        try {
            String response = switch (method) {
                case "GET" -> client.get().uri(uri).headers(h -> headers(h, method, uri, timestamp, bytes)).exchange((r, s) -> response(s));
                case "PATCH" -> client.patch().uri(uri).contentType(MediaType.APPLICATION_JSON).headers(h -> headers(h, method, uri, timestamp, bytes)).body(bytes).exchange((r, s) -> response(s));
                default -> client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).headers(h -> headers(h, method, uri, timestamp, bytes)).body(bytes).exchange((r, s) -> response(s));
            };
            return type != null ? objectMapper.readValue(response, type) : (T) objectMapper.readValue(response, listType);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "채팅 서비스를 사용할 수 없습니다.", exception);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "채팅 서비스 응답이 올바르지 않습니다.", exception);
        }
    }

    private String response(org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
        int status = response.getStatusCode().value();
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        if (status < 200 || status >= 300) {
            HttpStatus mapped = status == 404 ? HttpStatus.NOT_FOUND : status == 409 ? HttpStatus.CONFLICT : status == 422 ? HttpStatus.UNPROCESSABLE_ENTITY : status >= 500 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(mapped, body.isBlank() ? "채팅 요청을 처리할 수 없습니다." : body);
        }
        return body;
    }

    private void headers(org.springframework.http.HttpHeaders headers, String method, String uri, String timestamp, byte[] body) {
        headers.set("X-OMS-Chat-Timestamp", timestamp);
        headers.set("X-OMS-Chat-Signature", "v1=" + signature(timestamp, method, uri, body));
    }

    private String json(Object input) {
        try { return objectMapper.writeValueAsString(input); }
        catch (Exception exception) { throw new IllegalStateException("Unable to serialize chat request", exception); }
    }

    private String signature(String timestamp, String method, String uri, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestamp + "." + method + "." + uri + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception exception) { throw new IllegalStateException("Unable to sign chat request", exception); }
    }
}
