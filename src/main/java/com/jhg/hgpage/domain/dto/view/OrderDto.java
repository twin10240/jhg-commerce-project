package com.jhg.hgpage.domain.dto.view;

import com.jhg.hgpage.domain.enums.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
@AllArgsConstructor
public class OrderDto {
    private Long id;

    private OrderStatus status;

    private int totalAmount;

    private LocalDateTime createdAt;
}
