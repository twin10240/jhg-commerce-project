package com.jhg.hgpage.wms.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ReplenishmentRequestDto(
        Long id,
        UUID requestKey,
        String reason,
        String status,
        LocalDateTime requestedAt,
        LocalDateTime decidedAt,
        LocalDateTime fulfilledAt,
        String wmsMemo,
        Long purchaseOrderId,
        List<ItemDto> items
) {
    public record ItemDto(Long productId, int requestedQty) {}
}
