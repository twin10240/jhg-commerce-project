package com.jhg.hgpage.realtime.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.hgpage.realtime.chat.dto.ChatDtos;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class ChatDeliveryClientTest {
    private static final String SECRET = "test-chat-secret";
    private HttpServer server;
    private byte[] body;
    private String timestamp;
    private String signature;

    @BeforeEach void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/v1/chat/conversations", this::respond);
        server.start();
    }
    @AfterEach void tearDown() { server.stop(0); }

    @Test void UTF8_원문과_타임스탬프를_서명한다() throws Exception {
        ChatDeliveryClient client = new ChatDeliveryClient(RestClient.builder(), new ObjectMapper().findAndRegisterModules(), baseUrl(), SECRET);

        ChatDtos.Conversation result = client.createConversation("1", 7L);

        assertThat(result.orderId()).isEqualTo("1");
        assertThat(new String(body, StandardCharsets.UTF_8)).contains("customerMemberId");
        assertThat(signature).isEqualTo("v1=" + hmac(timestamp, body));
    }

    private void respond(HttpExchange exchange) throws java.io.IOException {
        body = exchange.getRequestBody().readAllBytes();
        timestamp = exchange.getRequestHeaders().getFirst("X-OMS-Chat-Timestamp");
        signature = exchange.getRequestHeaders().getFirst("X-OMS-Chat-Signature");
        byte[] response = "{\"id\":\"2e14515f-1c87-4caf-9654-0092efb0b23e\",\"orderId\":\"1\",\"customerMemberId\":\"7\",\"status\":\"OPEN\",\"lastMessageAt\":null,\"createdAt\":\"2026-09-04T00:00:00Z\",\"updatedAt\":\"2026-09-04T00:00:00Z\"}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(201, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
    private String baseUrl() { return "http://localhost:" + server.getAddress().getPort(); }
    private String hmac(String value, byte[] input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((value + ".").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(input));
    }
}
