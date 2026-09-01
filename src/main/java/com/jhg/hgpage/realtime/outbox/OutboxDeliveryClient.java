package com.jhg.hgpage.realtime.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "realtime.outbox.enabled", havingValue = "true")
public class OutboxDeliveryClient {

    private final RestClient restClient;
    private final byte[] secret;

    public OutboxDeliveryClient(RestClient.Builder builder,
                                @Value("${realtime.base-url}") String baseUrl,
                                @Value("${realtime.event-hmac-secret:}") String secret) {
        if (secret.isBlank()) {
            throw new IllegalStateException("realtime.event-hmac-secret must be configured when outbox delivery is enabled");
        }
        this.restClient = builder.baseUrl(baseUrl).build();
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public DeliveryResult deliver(UUID eventId, String payload, Instant now) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(now.getEpochSecond());
        try {
            int status = restClient.post()
                    .uri("/internal/v1/events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-OMS-Event-Id", eventId.toString())
                    .header("X-OMS-Timestamp", timestamp)
                    .header("X-OMS-Signature", "v1=" + signature(timestamp, body))
                    .body(body)
                    .exchange((request, response) -> response.getStatusCode().value());
            if (status == 200 || status == 201) {
                return DeliveryResult.success();
            }
            return status == 429 || status >= 500
                    ? DeliveryResult.retryable("HTTP_" + status)
                    : DeliveryResult.permanent("HTTP_" + status);
        } catch (RestClientException exception) {
            return DeliveryResult.retryable("IO_FAILURE");
        }
    }

    private String signature(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign realtime event", exception);
        }
    }
}
