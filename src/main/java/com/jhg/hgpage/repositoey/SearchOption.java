package com.jhg.hgpage.repositoey;

import com.jhg.hgpage.domain.enums.OrderStatus;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class SearchOption {
    private String userName;

    private OrderStatus orderStatus;
}
