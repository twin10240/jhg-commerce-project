package com.jhg.hgpage.realtime.outbox;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class OutboxDeliveryClientTest {

    private static final String SECRET = "test-hmac-secret";
    private static final UUID EVENT_ID = UUID.fromString("e133d8bf-9993-4d59-8b7a-c80fd2d2d37b");
    private static final Instant NOW = Instant.parse("2026-08-30T06:30:00Z");

    private HttpServer server;
    private int status;
    private byte[] receivedBody;
    private String receivedEventId;
    private String receivedTimestamp;
    private String receivedSignature;
    private boolean delayResponse;

    @BeforeEach
    void setUp() throws IOException {
        status = 201;
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/v1/events", this::respond);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 원본_UTF8_본문과_HMAC_헤더를_그대로_전송한다() throws Exception {
        String payload = "{\"eventId\":\"" + EVENT_ID + "\",\"message\":\"한글\"}";

        DeliveryResult result = client().deliver(EVENT_ID, payload, NOW);

        assertThat(result.outcome()).isEqualTo(DeliveryResult.Outcome.SUCCESS);
        assertThat(receivedBody).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
        assertThat(receivedEventId).isEqualTo(EVENT_ID.toString());
        assertThat(receivedTimestamp).isEqualTo("1788071400");
        assertThat(receivedSignature).isEqualTo("v1=" + signature(receivedTimestamp, receivedBody));
    }

    @Test
    void 응답상태를_성공_재시도_영구실패로_분류한다() {
        assertThat(client().deliver(EVENT_ID, "{}", NOW).outcome()).isEqualTo(DeliveryResult.Outcome.SUCCESS);

        status = 200;
        assertThat(client().deliver(EVENT_ID, "{}", NOW).outcome()).isEqualTo(DeliveryResult.Outcome.SUCCESS);

        status = 429;
        assertThat(client().deliver(EVENT_ID, "{}", NOW).outcome()).isEqualTo(DeliveryResult.Outcome.RETRYABLE_FAILURE);

        status = 500;
        assertThat(client().deliver(EVENT_ID, "{}", NOW).outcome()).isEqualTo(DeliveryResult.Outcome.RETRYABLE_FAILURE);

        status = 400;
        assertThat(client().deliver(EVENT_ID, "{}", NOW).outcome()).isEqualTo(DeliveryResult.Outcome.PERMANENT_FAILURE);
    }

    @Test
    void 읽기_시간초과는_재시도가능한_실패다() {
        delayResponse = true;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMillis(50));
        OutboxDeliveryClient client = new OutboxDeliveryClient(RestClient.builder().requestFactory(factory), baseUrl(), SECRET);

        assertThat(client.deliver(EVENT_ID, "{}", NOW).outcome()).isEqualTo(DeliveryResult.Outcome.RETRYABLE_FAILURE);
    }

    @Test
    void 발행기가_활성화된_상태에서_빈_비밀키를_거부한다() {
        assertThatIllegalStateException().isThrownBy(() ->
                new OutboxDeliveryClient(RestClient.builder(), baseUrl(), " "));
    }

    private OutboxDeliveryClient client() {
        return new OutboxDeliveryClient(RestClient.builder(), baseUrl(), SECRET);
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange) throws IOException {
        receivedBody = exchange.getRequestBody().readAllBytes();
        receivedEventId = exchange.getRequestHeaders().getFirst("X-OMS-Event-Id");
        receivedTimestamp = exchange.getRequestHeaders().getFirst("X-OMS-Timestamp");
        receivedSignature = exchange.getRequestHeaders().getFirst("X-OMS-Signature");
        try {
            if (delayResponse) {
                Thread.sleep(200);
            }
            exchange.sendResponseHeaders(status, -1);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private String signature(String timestamp, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(payload));
    }
}
