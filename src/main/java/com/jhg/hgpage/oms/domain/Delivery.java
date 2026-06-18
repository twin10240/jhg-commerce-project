package com.jhg.hgpage.oms.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

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
}
