package com.jhg.hgpage.oms.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import static jakarta.persistence.FetchType.LAZY;

@Entity
@Getter @Setter
public class Delivery {
    @Id
    @GeneratedValue
    @Column(name = "delivery_id")
    private Long id;

    @OneToOne(mappedBy = "delivery", fetch = LAZY)
    private Order order;

    @Embedded
    private Address address;

    @Enumerated(EnumType.STRING) // ORDINAL은 enum 순서 변경 시 기존 데이터가 깨진다 (Order.status와 일관)
    private DeliveryStatus status;

    @Column(length = 30)
    private String carrierCode;

    @Column(length = 100)
    private String carrierName;

    @Column(length = 100)
    private String trackingNumber;
    private Instant shipmentIssuedAt;
    private Instant deliveredAt;
    @Column(nullable = false)
    private long deliveryVersion;
    private Instant deliveryStatusAt;
    private Instant deliveryFailedAt;
    @Column(length = 30)
    private String deliveryFailureCode;

    public static void validateStatusEvent(DeliveryStatus status, long version, Instant at, String failureCode) {
        if (status == null || status == DeliveryStatus.READY || version < 1 || at == null
                || status != DeliveryStatus.SHIPPED && version < 2) {
            throw new IllegalArgumentException("Invalid delivery status event");
        }
        if (status == DeliveryStatus.DELIVERY_FAILED
                ? !"RECIPIENT_UNAVAILABLE".equals(failureCode) && !"ADDRESS_ISSUE".equals(failureCode)
                : failureCode != null) {
            throw new IllegalArgumentException("Invalid delivery failure code");
        }
    }

    /** WMS snapshots may skip intermediate events; versions prevent regression and duplicate work. */
    public boolean applyStatus(DeliveryStatus next, long version, Instant at, String failureCode) {
        validateStatusEvent(next, version, at, failureCode);
        Instant normalizedAt = at.truncatedTo(ChronoUnit.MICROS);
        if (version < deliveryVersion) return false;
        if (version == deliveryVersion) {
            String currentFailure = status == DeliveryStatus.DELIVERY_FAILED ? deliveryFailureCode : null;
            if (status != next || !Objects.equals(deliveryStatusAt, normalizedAt)
                    || !Objects.equals(currentFailure, failureCode)) {
                throw new IllegalStateException("Conflicting delivery event version");
            }
            return false;
        }
        if (status == DeliveryStatus.READY || status == DeliveryStatus.DELIVERED && next != DeliveryStatus.DELIVERED) {
            throw new IllegalStateException("Invalid delivery state transition");
        }
        if (deliveryVersion > 0) {
            // Each retry cycle is failure → shipped; skipped snapshots must still be reachable.
            boolean evenDistance = (version - deliveryVersion) % 2 == 0;
            boolean requiresEvenDistance = status == next
                    || status == DeliveryStatus.DELIVERY_FAILED && next == DeliveryStatus.DELIVERED;
            if (status == DeliveryStatus.DELIVERED || evenDistance != requiresEvenDistance) {
                throw new IllegalStateException("Unreachable delivery state version");
            }
        }
        status = next;
        deliveryVersion = version;
        deliveryStatusAt = normalizedAt;
        if (next == DeliveryStatus.DELIVERY_FAILED) {
            deliveryFailedAt = normalizedAt;
            deliveryFailureCode = failureCode;
        }
        if (next == DeliveryStatus.DELIVERED) recordDeliveredAt(normalizedAt);
        return true;
    }

    public void recordShipment(String carrierCode, String carrierName,
                               String trackingNumber, Instant issuedAt) {
        this.carrierCode = carrierCode;
        this.carrierName = carrierName;
        this.trackingNumber = trackingNumber;
        this.shipmentIssuedAt = issuedAt;
    }

    public void recordDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = Objects.requireNonNull(deliveredAt);
    }
}
