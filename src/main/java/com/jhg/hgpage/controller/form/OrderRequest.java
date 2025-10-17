package com.jhg.hgpage.controller.form;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter @Setter
public class OrderRequest {
     // 단건용 (메인 페이지용)
     private Long productId;
     private Integer qty;

     // 다건용 (장바구니용)
     private List<OrderItem> items = new ArrayList<>();

     @Getter @Setter
     public static class OrderItem {
        @NotNull
        private Long productId;

        @NotNull @Min(1)
        private Integer qty;
    }
}