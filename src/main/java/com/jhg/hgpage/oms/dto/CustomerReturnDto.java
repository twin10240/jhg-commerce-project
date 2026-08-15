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
    private final String statusLabel;
    private final String reason;
    private final LocalDateTime requestedAt;
    private final LocalDateTime updatedAt;
    private final List<Item> items;

    private CustomerReturnDto(CustomerReturn customerReturn) {
        id = customerReturn.getId();
        orderId = customerReturn.getOrder().getId();
        statusLabel = statusLabel(customerReturn.getStatus());
        reason = customerReturn.getReason();
        requestedAt = customerReturn.getRequestedAt();
        updatedAt = customerReturn.getUpdatedAt();
        items = customerReturn.getItems().stream()
                .map(item -> Item.from(item, claimedQuantity(customerReturn.getStatus(), item)))
                .toList();
    }

    public static CustomerReturnDto from(CustomerReturn customerReturn) {
        return new CustomerReturnDto(customerReturn);
    }

    private static int claimedQuantity(CustomerReturnStatus status, CustomerReturnItem item) {
        return switch (status) {
            case PENDING_SUBMISSION, REQUESTED, RECEIVED -> item.getRequestedQuantity();
            case COMPLETED -> item.getAcceptedQuantity();
            case SUBMISSION_FAILED, CANCELLED -> 0;
        };
    }

    private static String statusLabel(CustomerReturnStatus status) {
        return switch (status) {
            case PENDING_SUBMISSION -> "WMS 전송 중";
            case SUBMISSION_FAILED -> "접수 실패";
            case REQUESTED -> "반품 접수";
            case RECEIVED -> "창고 입고";
            case COMPLETED -> "반품 완료";
            case CANCELLED -> "반품 취소";
        };
    }

    private static String dispositionLabel(ReturnDisposition disposition) {
        if (disposition == null) {
            return null;
        }
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
        private final String dispositionLabel;
        private final int claimedQuantity;

        private Item(Long orderItemId, String productName, int requestedQuantity, Integer acceptedQuantity,
                     String dispositionLabel, int claimedQuantity) {
            this.orderItemId = orderItemId;
            this.productName = productName;
            this.requestedQuantity = requestedQuantity;
            this.acceptedQuantity = acceptedQuantity;
            this.dispositionLabel = dispositionLabel;
            this.claimedQuantity = claimedQuantity;
        }

        private static Item from(CustomerReturnItem item, int claimedQuantity) {
            return new Item(item.getOrderItem().getId(), item.getOrderItem().getProduct().getName(),
                    item.getRequestedQuantity(), item.getAcceptedQuantity(),
                    CustomerReturnDto.dispositionLabel(item.getDisposition()), claimedQuantity);
        }
    }
}
