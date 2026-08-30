package com.jhg.hgpage.realtime.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

@SpringBootTest
@Transactional
class NotificationEventWriterTest {

    @Autowired NotificationEventWriter writer;
    @Autowired NotificationOutboxRepository repository;
    @Autowired ObjectMapper objectMapper;

    @Test
    void 알림이벤트는_개인정보없이_버전1_봉투로_저장된다() throws Exception {
        UUID eventId = writer.append(NotificationEventType.DELIVERY_COMPLETED, 7L,
                "ORDER", "12", Map.of("orderId", 12));

        NotificationOutbox outbox = repository.findByEventId(eventId).orElseThrow();
        JsonNode payload = objectMapper.readTree(outbox.getPayload());

        assertThat(outbox.getId()).isNotEqualTo(eventId);
        assertThat(outbox.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(payload.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(payload.get("type").asText()).isEqualTo("DELIVERY_COMPLETED");
        assertThat(Instant.parse(payload.get("occurredAt").asText())).isNotNull();
        assertThat(payload.get("occurredAt").asText()).endsWith("Z");
        assertThat(payload.get("recipientId").asLong()).isEqualTo(7L);
        assertThat(payload.at("/aggregate/type").asText()).isEqualTo("ORDER");
        assertThat(payload.at("/aggregate/id").asText()).isEqualTo("12");
        assertThat(payload.at("/data/orderId").asInt()).isEqualTo(12);
        assertThat(payload.has("name")).isFalse();
        assertThat(payload.has("email")).isFalse();
        assertThat(payload.has("phone")).isFalse();
        assertThat(payload.has("address")).isFalse();
        assertThat(payload.has("title")).isFalse();
        assertThat(payload.has("body")).isFalse();
    }

    @Test
    void 직렬화실패는_Outbox행을_저장하지_않는다() {
        assertThatIllegalStateException().isThrownBy(() -> writer.append(
                NotificationEventType.DELIVERY_COMPLETED, 7L, "ORDER", "12",
                Map.of("orderId", new Object())));

        assertThat(repository.count()).isZero();
    }
}
