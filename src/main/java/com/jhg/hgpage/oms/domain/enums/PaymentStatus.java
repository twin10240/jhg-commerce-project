package com.jhg.hgpage.oms.domain.enums;

public enum PaymentStatus {
    PENDING("결제 대기"),
    PAYMENT_FAILED("결제 실패"),
    PAYMENT_REVIEW("결제 확인 필요"),
    PAID("결제 완료"),
    PARTIALLY_REFUNDED("부분 환불"),
    REFUNDED("환불 완료"),
    CANCELLED("결제 취소");

    private final String label;

    PaymentStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
