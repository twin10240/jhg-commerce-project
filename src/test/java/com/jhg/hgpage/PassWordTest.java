package com.jhg.hgpage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
public class PassWordTest {
    @Autowired
    PasswordEncoder passwordEncoder; // DI

    BCryptPasswordEncoder bCryptPasswordEncoder = new BCryptPasswordEncoder(12);

    @Test
    public void pwdEnc() {
        String pwd = "Qwer1234!";
        String encodedPwd = passwordEncoder.encode(pwd); //암호화 하는부분
        String encodedPwd2 = bCryptPasswordEncoder.encode(pwd); //암호화 하는부분
        System.out.println(encodedPwd);
        System.out.println(encodedPwd2);

        System.err.println("---------------------------------------------");

        System.err.println(passwordEncoder.matches(pwd, encodedPwd));
        System.err.println(passwordEncoder.matches(pwd, encodedPwd2));
    }

}
