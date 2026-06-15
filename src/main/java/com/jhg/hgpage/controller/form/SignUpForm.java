package com.jhg.hgpage.controller.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class SignUpForm {
    @NotEmpty(message = "E-mail은 필수입니다.")
    private String email;
    @NotEmpty(message = "비밀번호는 필수입니다.")
    private String password;
    @NotBlank(message = "비밀번호 확인은 필수입니다.")
    private String passwordConfirm;
    @NotBlank(message = "이름은 필수입니다.")
    private String name;
    @NotBlank(message = "연락처는 필수입니다.")
    private String phone;
    @NotBlank(message = "도시는 필수입니다.")
    private String city;
    @NotBlank(message = "상세주소는 필수입니다.")
    private String street;
    @NotBlank(message = "우편번호는 필수입니다.")
    private String zipcode;
}
