package com.jhg.hgpage.oms.domain.enums;

public enum OrderStatus {
    PAYMENT_PENDING,
    PAYMENT_FAILED,
    PAYMENT_REVIEW,
    ALLOCATION_PENDING,
    ALLOCATION_PROCESSING,
    ALLOCATION_REVIEW,
    CANCEL_REQUESTED,
    ORDER,       // 접수 + 재고 예약 완료
    BACKORDERED, // 재고 부족으로 입고 대기 (예약 없음)
    CANCEL
}
