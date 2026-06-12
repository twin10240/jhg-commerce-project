package com.jhg.hgpage.controller.form;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

    // 장바구니에서 온 주문서인지 여부. true면 주문 확정 시 주문된 상품을 장바구니에서 제거한다.
    // (조작해도 자기 장바구니의 정리 여부만 달라지므로 보안 영향 없음)
    private boolean fromCart;

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
    public static class ProductDto {
        @NotNull
        private Long id;
        private String name;
        private int price;
        @Min(value = 1, message = "수량은 1개 이상이어야 합니다.")
        private int quantity;
        // 주문서에서 체크 해제하면 주문에서 제외. 기본 true라 단건 구매/기존 흐름은 그대로 동작한다.
        private boolean selected = true;

        public ProductDto(Long id, String name, int price, int quantity) {
            this.id = id;
            this.name = name;
            this.price = price;
            this.quantity = quantity;
        }
    }
}
