package com.jhg.hgpage.oms.repository;

import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class SearchOption {
    private String productName;

    private OrderStatus orderStatus;
}
