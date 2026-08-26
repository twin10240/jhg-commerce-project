package com.jhg.hgpage.oms.domain.enums;

public enum RefundStatus {
    PENDING("환불 대기"),
    PROCESSING("환불 처리 중"),
    RETRYING("환불 재시도 대기"),
    SUCCEEDED("환불 완료"),
    MANUAL_REVIEW("환불 확인 필요");

    private final String label;

    RefundStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
