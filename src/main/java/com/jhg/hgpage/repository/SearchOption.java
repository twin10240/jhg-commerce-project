package com.jhg.hgpage.repository;

import com.jhg.hgpage.domain.enums.OrderStatus;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class SearchOption {
    private String productName;

    private OrderStatus orderStatus;
}
