package com.jhg.hgpage.oms.dto;

import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;

import java.time.LocalDateTime;
import java.util.List;

public record AdminCustomerReturnDto(
        Long id, Long orderId, String customerName,
        CustomerReturnStatus status, String statusLabel,
        String reason, String reviewedBy, LocalDateTime reviewedAt,
        String rejectionReason, String failureReason,
        LocalDateTime requestedAt, List<Item> items) {

    public record Item(String productName, int quantity) {}

    public static AdminCustomerReturnDto from(CustomerReturn value) {
        return new AdminCustomerReturnDto(
                value.getId(), value.getOrder().getId(), value.getOrder().getMember().getName(),
                value.getStatus(), value.getStatus().getLabel(), value.getReason(),
                value.getReviewedBy(), value.getReviewedAt(), value.getRejectionReason(),
                value.getFailureReason(), value.getRequestedAt(),
                value.getItems().stream()
                        .map(item -> new Item(item.getOrderItem().getProduct().getName(),
                                item.getRequestedQuantity()))
                        .toList());
    }
}
