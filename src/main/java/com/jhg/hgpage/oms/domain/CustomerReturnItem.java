package com.jhg.hgpage.oms.domain;

import com.jhg.hgpage.oms.domain.enums.ReturnDisposition;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static jakarta.persistence.FetchType.LAZY;

@Entity
@Getter
@Table(name = "customer_return_item", uniqueConstraints =
        @UniqueConstraint(name = "uq_customer_return_order_item", columnNames = {"customer_return_id", "order_item_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerReturnItem {

    @Id @GeneratedValue
    @Column(name = "customer_return_item_id")
    private Long id;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "customer_return_id", nullable = false)
    private CustomerReturn customerReturn;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    @Column(nullable = false)
    private int requestedQuantity;

    private Integer acceptedQuantity;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ReturnDisposition disposition;

    static CustomerReturnItem create(CustomerReturn customerReturn, OrderItem orderItem, int requestedQuantity) {
        if (orderItem == null || requestedQuantity <= 0) {
            throw new IllegalArgumentException("반품 품목과 수량은 필수입니다.");
        }
        CustomerReturnItem item = new CustomerReturnItem();
        item.customerReturn = customerReturn;
        item.orderItem = orderItem;
        item.requestedQuantity = requestedQuantity;
        return item;
    }

    void validateResult(int acceptedQuantity, ReturnDisposition disposition) {
        if (acceptedQuantity < 0 || acceptedQuantity > requestedQuantity) {
            throw new IllegalArgumentException("승인 수량이 요청 수량 범위를 벗어났습니다.");
        }
        if (acceptedQuantity == 0 && disposition != ReturnDisposition.REJECTED) {
            throw new IllegalArgumentException("승인 0은 REJECTED만 허용합니다.");
        }
        if (acceptedQuantity > 0 && disposition != ReturnDisposition.RESTOCKED
                && disposition != ReturnDisposition.DISPOSED) {
            throw new IllegalArgumentException("승인 수량이 있으면 RESTOCKED 또는 DISPOSED여야 합니다.");
        }
    }

    void applyResult(int acceptedQuantity, ReturnDisposition disposition) {
        validateResult(acceptedQuantity, disposition);
        this.acceptedQuantity = acceptedQuantity;
        this.disposition = disposition;
    }

    void cancel() {
        this.acceptedQuantity = 0;
        this.disposition = null;
    }
}
