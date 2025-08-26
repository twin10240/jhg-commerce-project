package com.jhg.hgpage.controller.main;

import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.service.MemberService;
import com.jhg.hgpage.service.ProductService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class MainController {
    private final MemberService memberService;
    private final ProductService productService;

    @PostMapping("/login")
    public String logIn(HttpServletRequest request, Model model) {
        Member member = memberService.findMemberByEmail(request.getParameter("email"));
        model.addAttribute("member", member);

        List<Product> products = productService.findAll();
        model.addAttribute("products", products);

        return "/main";
    }

    @GetMapping("/logout")
    public String logOut() {
        return "/";
    }
}
