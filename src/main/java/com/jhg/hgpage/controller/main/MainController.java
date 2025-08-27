package com.jhg.hgpage.controller.main;

import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Order;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.service.MemberService;
import com.jhg.hgpage.service.OrderService;
import com.jhg.hgpage.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class MainController {
    private final MemberService memberService;
    private final ProductService productService;
    private final OrderService orderService;

    @GetMapping("/main")
    public String logIn(@AuthenticationPrincipal UserPrincipal userPrincipal, Model model) {
//        Member member = memberService.findMemberByEmail(userPrincipal.getUsername());
        Member member = memberService.findMemberByEmailWithQueryDsl(userPrincipal.getUsername());
        model.addAttribute("member", member);

        List<Product> products = productService.findAll();
        model.addAttribute("products", products);

        List<Order> orders = orderService.findOrders();
        model.addAttribute("orders", products);

        model.addAttribute("role", userPrincipal.getAuthorities());

        return "main";
    }
}
