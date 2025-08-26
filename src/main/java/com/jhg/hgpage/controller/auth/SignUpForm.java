package com.jhg.hgpage.controller.auth;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class SignUpForm {
    @NotEmpty(message = "E-mail은 필수입니다.")
    private String email;
    @NotEmpty(message = "비밀번호는 필수입니다.")
    private String password;
    private String passwordConfirm;
    private String name;
    private String phone;
    private String city;
    private String street;
    private String zipcode;
}
