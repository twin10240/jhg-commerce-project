package com.jhg.hgpage.domain;

import com.jhg.hgpage.wms.domain.Reservation;
import com.jhg.hgpage.wms.domain.enums.ReservationStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reservation = 주문(orderId)당 재고 예약의 멱등 원장. 상태 전이만 책임진다.
 * RESERVED → SHIPPED(출고) 또는 RESERVED → RELEASED(취소). 그 외 전이는 거부.
 */
class ReservationTest {

    @Test
    void 예약을_생성하면_RESERVED_상태이고_orderId를_보유한다() {
        Reservation r = Reservation.reserve(10L);

        assertThat(r.getOrderId()).isEqualTo(10L);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void 출고하면_SHIPPED가_된다() {
        Reservation r = Reservation.reserve(10L);

        r.ship();

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.SHIPPED);
    }

    @Test
    void 해제하면_RELEASED가_된다() {
        Reservation r = Reservation.reserve(10L);

        r.release();

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void 이미_해제된_예약은_출고할_수_없다() {
        Reservation r = Reservation.reserve(10L);
        r.release();

        assertThatThrownBy(r::ship).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 이미_출고된_예약은_해제할_수_없다() {
        Reservation r = Reservation.reserve(10L);
        r.ship();

        assertThatThrownBy(r::release).isInstanceOf(IllegalStateException.class);
    }
}
