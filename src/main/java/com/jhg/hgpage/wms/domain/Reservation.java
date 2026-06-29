package com.jhg.hgpage.wms.domain;

import com.jhg.hgpage.wms.domain.enums.ReservationStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문(orderId)당 재고 예약 원장. orderId 자연키로 멱등성을 보장한다
 * (같은 주문의 예약/출고/해제 재요청은 서비스가 이 상태를 보고 no-op 처리).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id @GeneratedValue
    @Column(name = "reservation_id")
    private Long id;

    @Column(unique = true, nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    public static Reservation reserve(Long orderId) {
        Reservation reservation = new Reservation();
        reservation.orderId = orderId;
        reservation.status = ReservationStatus.RESERVED;
        return reservation;
    }

    public void ship() {
        if (status != ReservationStatus.RESERVED) {
            throw new IllegalStateException("예약(RESERVED) 상태에서만 출고할 수 있습니다. 현재: " + status);
        }
        this.status = ReservationStatus.SHIPPED;
    }

    public void release() {
        if (status != ReservationStatus.RESERVED) {
            throw new IllegalStateException("예약(RESERVED) 상태에서만 해제할 수 있습니다. 현재: " + status);
        }
        this.status = ReservationStatus.RELEASED;
    }
}
