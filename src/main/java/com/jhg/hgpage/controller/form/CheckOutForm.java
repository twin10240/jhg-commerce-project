package com.jhg.hgpage.controller.form;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
public class CheckOutForm {
    // checkout()에서 사용하지 않고, 화면에서 이름/이메일이 disabled라 전송되지 않으므로 검증 제외
    private MemberDto member = new MemberDto();
    @Valid
    private DeliveryDto delivery = new DeliveryDto();

    @Valid
    @Size(min = 1, message = "주문할 상품이 없습니다.")
    private List<ProductDto> product = new ArrayList<>();

    public int getProductCount() {
        return product.size();
    }

    public int getProductTotalPrice() {
        return product.stream()
                .mapToInt(item -> item.getPrice() * item.getQuantity())
                .sum();
    }

    @Data
    public static class MemberDto {
        @NotBlank
        private String name;
        @Email @NotBlank
        private String email;
        @NotBlank
        private String phone;
    }

    @Data
    public static class DeliveryDto {
        @NotBlank
        private String city;
        @NotBlank
        private String street;
        @NotBlank
        private String zipcode;
        private boolean saveAsDefault; // 체크박스
        private String memo;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductDto {
        @NotNull
        private Long id;
        private String name;
        private int price;
        @Min(value = 1, message = "수량은 1개 이상이어야 합니다.")
        private int quantity;
    }
}
