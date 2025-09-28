package com.jhg.hgpage.domain.dto.form;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CheckOutForm {
    @Valid
    private MemberDto member = new MemberDto();
    @Valid
    private DeliveryDto delivery = new DeliveryDto();

    private List<ProductDto> product = new ArrayList<>();

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
    @AllArgsConstructor
    public static class ProductDto {
        private Long id;
        private String name;
        private int price;
        private int quantity;
    }
}
