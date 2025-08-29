package com.jhg.hgpage.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CheckOutForm {
    @Valid
    private MemberDto member = new MemberDto();
    @Valid
    private DeliveryDto delivery = new DeliveryDto();

    private ProductDto product = new ProductDto();

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
    public static class ProductDto {

    }
}
