package com.jhg.hgpage.wms.dto;

import java.time.LocalDateTime;
import java.util.List;

public record PurchaseOrderDto(
        Long id,
        String status,
        String memo,
        LocalDateTime createdAt,
        LocalDateTime receivedAt,
        List<ItemDto> items
) {
    public record ItemDto(Long id, Long productId, int quantity) {}
}
