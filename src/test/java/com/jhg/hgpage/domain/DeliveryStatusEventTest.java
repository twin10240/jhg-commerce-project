package com.jhg.hgpage.domain;

import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryStatusEventTest {
    private static final Instant FAILED_AT = Instant.parse("2026-10-14T00:00:00.123456789Z");

    private Delivery shipped() {
        Delivery delivery = new Delivery();
        delivery.setStatus(DeliveryStatus.SHIPPED);
        delivery.recordShipment("MOCK", "테스트택배", "MOCK-10", Instant.parse("2026-10-13T00:00:00Z"));
        return delivery;
    }

    @Test
    void 실패_재배송_완료를_같은_송장에_기록한다() {
        Delivery delivery = shipped();
        assertThat(delivery.applyStatus(DeliveryStatus.DELIVERY_FAILED, 2, FAILED_AT, "RECIPIENT_UNAVAILABLE")).isTrue();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DELIVERY_FAILED);
        assertThat(delivery.getDeliveryFailedAt()).isEqualTo(Instant.parse("2026-10-14T00:00:00.123456Z"));
        assertThat(delivery.getDeliveryFailureCode()).isEqualTo("RECIPIENT_UNAVAILABLE");
        assertThat(delivery.applyStatus(DeliveryStatus.SHIPPED, 3, FAILED_AT.plusSeconds(1), null)).isTrue();
        assertThat(delivery.applyStatus(DeliveryStatus.DELIVERED, 4, FAILED_AT.plusSeconds(2), null)).isTrue();
        assertThat(delivery.getDeliveredAt()).isEqualTo(Instant.parse("2026-10-14T00:00:02.123456Z"));
        assertThat(delivery.getTrackingNumber()).isEqualTo("MOCK-10");
        assertThat(delivery.getDeliveryVersion()).isEqualTo(4);
    }

    @Test
    void 중복과_늦은_실패는_상태와_완료시각을_바꾸지_않는다() {
        Delivery delivery = shipped();
        delivery.applyStatus(DeliveryStatus.DELIVERED, 4, FAILED_AT, null);
        assertThat(delivery.applyStatus(DeliveryStatus.DELIVERED, 4, Instant.parse("2026-10-14T00:00:00.123456Z"), null)).isFalse();
        assertThat(delivery.applyStatus(DeliveryStatus.DELIVERY_FAILED, 2, FAILED_AT.minusSeconds(1), "ADDRESS_ISSUE")).isFalse();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivery.getDeliveryVersion()).isEqualTo(4);
    }

    @Test
    void 같은_버전의_다른_내용은_거부한다() {
        Delivery delivery = shipped();
        delivery.applyStatus(DeliveryStatus.DELIVERY_FAILED, 2, FAILED_AT, "ADDRESS_ISSUE");
        assertThatThrownBy(() -> delivery.applyStatus(DeliveryStatus.DELIVERY_FAILED, 2, FAILED_AT, "RECIPIENT_UNAVAILABLE"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> delivery.applyStatus(DeliveryStatus.DELIVERY_FAILED, 2, FAILED_AT.plusSeconds(1), "ADDRESS_ISSUE"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 중간_버전이_빠져도_최신_완료로_수렴한다() {
        Delivery delivery = shipped();
        delivery.applyStatus(DeliveryStatus.DELIVERY_FAILED, 2, FAILED_AT, "ADDRESS_ISSUE");
        delivery.applyStatus(DeliveryStatus.DELIVERED, 4, FAILED_AT.plusSeconds(2), null);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test
    void 아직_출고하지_않은_주문과_완료_주문의_새_실패는_거부한다() {
        Delivery ready = new Delivery();
        ready.setStatus(DeliveryStatus.READY);
        assertThatThrownBy(() -> ready.applyStatus(DeliveryStatus.DELIVERY_FAILED, 2, FAILED_AT, "ADDRESS_ISSUE"))
                .isInstanceOf(IllegalStateException.class);
        Delivery delivered = shipped();
        delivered.applyStatus(DeliveryStatus.DELIVERED, 4, FAILED_AT, null);
        assertThatThrownBy(() -> delivered.applyStatus(DeliveryStatus.DELIVERY_FAILED, 5, FAILED_AT.plusSeconds(1), "ADDRESS_ISSUE"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 잘못된_버전과_실패코드를_거부한다() {
        Delivery delivery = shipped();
        assertThatThrownBy(() -> delivery.applyStatus(DeliveryStatus.DELIVERY_FAILED, 0, FAILED_AT, "ADDRESS_ISSUE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> delivery.applyStatus(DeliveryStatus.DELIVERY_FAILED, 2, FAILED_AT, "PRIVATE_REASON"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> delivery.applyStatus(DeliveryStatus.SHIPPED, 3, FAILED_AT, "ADDRESS_ISSUE"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void 실패_직후_다음버전의_완료와_실패는_중간_재배송이_없으므로_거부한다() {
        Delivery delivery = shipped();
        delivery.applyStatus(DeliveryStatus.DELIVERY_FAILED, 2, FAILED_AT, "ADDRESS_ISSUE");
        assertThatThrownBy(() -> delivery.applyStatus(DeliveryStatus.DELIVERED, 3, FAILED_AT.plusSeconds(1), null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> delivery.applyStatus(DeliveryStatus.DELIVERY_FAILED, 3, FAILED_AT.plusSeconds(1), "RECIPIENT_UNAVAILABLE"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(delivery.getDeliveryVersion()).isEqualTo(2);
        assertThat(delivery.getDeliveryFailureCode()).isEqualTo("ADDRESS_ISSUE");
    }

    @Test
    void 완료된_버전배송은_더_높은_완료버전으로_시각을_변경하지_않는다() {
        Delivery delivery = shipped();
        delivery.applyStatus(DeliveryStatus.DELIVERED, 4, FAILED_AT, null);
        assertThatThrownBy(() -> delivery.applyStatus(DeliveryStatus.DELIVERED, 5, FAILED_AT.plusSeconds(1), null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(delivery.getDeliveredAt()).isEqualTo(Instant.parse("2026-10-14T00:00:00.123456Z"));
    }

    @Test
    void 출고_다음버전은_재배송일수_없지만_실패를_건너뛴_재배송은_가능하다() {
        Delivery delivery = shipped();
        delivery.applyStatus(DeliveryStatus.SHIPPED, 1, FAILED_AT, null);
        assertThatThrownBy(() -> delivery.applyStatus(DeliveryStatus.SHIPPED, 2, FAILED_AT.plusSeconds(1), null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(delivery.applyStatus(DeliveryStatus.SHIPPED, 3, FAILED_AT.plusSeconds(2), null)).isTrue();
    }

}
