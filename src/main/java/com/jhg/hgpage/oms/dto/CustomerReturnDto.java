package com.jhg.hgpage.oms.dto;

import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.CustomerReturnItem;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.domain.enums.ReturnDisposition;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
public class CustomerReturnDto {

    private final Long id;
    private final Long orderId;
    private final CustomerReturnStatus status;
    private final String statusLabel;
    private final String failureReasonLabel;
    private final String rejectionReason;
    private final String reason;
    private final LocalDateTime requestedAt;
    private final LocalDateTime updatedAt;
    private final List<Item> items;

    private CustomerReturnDto(CustomerReturn customerReturn) {
        id = customerReturn.getId();
        orderId = customerReturn.getOrder().getId();
        status = customerReturn.getStatus();
        statusLabel = status.getLabel();
        failureReasonLabel = failureReasonLabel(customerReturn.getFailureReason());
        rejectionReason = customerReturn.getRejectionReason();
        reason = customerReturn.getReason();
        requestedAt = customerReturn.getRequestedAt();
        updatedAt = customerReturn.getUpdatedAt();
        items = customerReturn.getItems().stream()
                .map(item -> Item.from(customerReturn.getStatus(), item,
                        claimedQuantity(customerReturn.getStatus(), item)))
                .toList();
    }

    public static CustomerReturnDto from(CustomerReturn customerReturn) {
        return new CustomerReturnDto(customerReturn);
    }

    private static int claimedQuantity(CustomerReturnStatus status, CustomerReturnItem item) {
        return switch (status) {
            case PENDING_APPROVAL, PENDING_SUBMISSION, REQUESTED, RECEIVED -> item.getRequestedQuantity();
            case COMPLETED -> item.getAcceptedQuantity();
            case SUBMISSION_FAILED, CANCELLED, REJECTED -> 0;
        };
    }

    private static String failureReasonLabel(String failureReason) {
        if (failureReason == null) return null;
        return switch (failureReason) {
            case "BAD_REQUEST" -> "반품 요청 정보가 올바르지 않거나 반품 가능 수량을 초과했습니다.";
            case "CONFLICT" -> "이미 처리된 반품 요청과 충돌했습니다.";
            default -> "WMS에서 반품 요청을 처리할 수 없습니다.";
        };
    }

    private static String resultLabel(CustomerReturnStatus status, ReturnDisposition disposition) {
        if (status == CustomerReturnStatus.CANCELLED) return "취소";
        if (status == CustomerReturnStatus.SUBMISSION_FAILED) return "접수 실패";
        if (status == CustomerReturnStatus.REJECTED) return "반품 반려";
        if (disposition == null) return "처리 중";
        return switch (disposition) {
            case RESTOCKED -> "재입고";
            case DISPOSED -> "폐기";
            case REJECTED -> "거절";
        };
    }

    @Getter
    public static class Item {
        private final Long orderItemId;
        private final String productName;
        private final int requestedQuantity;
        private final Integer acceptedQuantity;
        private final String resultLabel;
        private final int claimedQuantity;

        private Item(Long orderItemId, String productName, int requestedQuantity, Integer acceptedQuantity,
                     String resultLabel, int claimedQuantity) {
            this.orderItemId = orderItemId;
            this.productName = productName;
            this.requestedQuantity = requestedQuantity;
            this.acceptedQuantity = acceptedQuantity;
            this.resultLabel = resultLabel;
            this.claimedQuantity = claimedQuantity;
        }

        private static Item from(CustomerReturnStatus status, CustomerReturnItem item, int claimedQuantity) {
            return new Item(item.getOrderItem().getId(), item.getOrderItem().getProduct().getName(),
                    item.getRequestedQuantity(), item.getAcceptedQuantity(),
                    CustomerReturnDto.resultLabel(status, item.getDisposition()), claimedQuantity);
        }
    }
}
