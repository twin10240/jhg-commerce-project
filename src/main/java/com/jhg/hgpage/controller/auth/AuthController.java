package com.jhg.hgpage.controller.auth;

import com.jhg.hgpage.controller.form.SignUpForm;
import com.jhg.hgpage.domain.Account;
import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.enums.Role;
import com.jhg.hgpage.exception.DuplicateEmailException;
import com.jhg.hgpage.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
@RequiredArgsConstructor
public class AuthController {
    private final AccountService accountService;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/signup")
    public String signUpFrom(Model model) {
        model.addAttribute("signUpForm", new SignUpForm());

        return "signup";
    }

    @PostMapping("/signup")
    public String signUp(@Valid SignUpForm form, BindingResult result) {
        if(result.hasErrors()){
            return "signup";
        }

        Member member = Member.createUser(form.getName(), form.getPhone(), new Address(form.getCity(), form.getStreet(), form.getZipcode()));
        Account account = new Account(form.getEmail(), passwordEncoder.encode(form.getPassword()), member, Role.USER);

        try {
            accountService.signUp(member, account);
        } catch (DuplicateEmailException e) {
            result.rejectValue("email", "duplicate", "이미 사용 중인 이메일입니다.");
            return "signup";
        }

        return "redirect:/";
    }

    @GetMapping("/login")
    public String login() {
        return "home";
    }

    @GetMapping("/logout")
    public String logOut() {
        return "redirect:/?logout";
    }
}
