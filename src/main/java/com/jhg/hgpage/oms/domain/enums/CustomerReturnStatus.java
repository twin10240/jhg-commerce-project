package com.jhg.hgpage.oms.domain.enums;

public enum CustomerReturnStatus {
    PENDING_APPROVAL("OMS 승인 대기"),
    PENDING_SUBMISSION("WMS 전송 중"),
    SUBMISSION_FAILED("접수 실패"),
    REQUESTED("반품 접수"),
    RECEIVED("창고 입고"),
    COMPLETED("반품 완료"),
    CANCELLED("반품 취소"),
    REJECTED("반품 반려");

    private final String label;

    CustomerReturnStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
