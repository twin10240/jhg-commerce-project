package com.jhg.hgpage.controller.main;

import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Order;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.domain.dto.OrderDto;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.repositoey.SearchOption;
import com.jhg.hgpage.service.MemberService;
import com.jhg.hgpage.service.OrderService;
import com.jhg.hgpage.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class MainController {
    private final MemberService memberService;
    private final ProductService productService;
    private final OrderService orderService;

    @GetMapping("/main")
    public String logIn(@AuthenticationPrincipal UserPrincipal userPrincipal, @ModelAttribute("searchOption") SearchOption searchOption, Model model) {
//        Member member = memberService.findMemberByEmail(userPrincipal.getEmail());
        Member member = memberService.findMemberByEmailWithQueryDsl(userPrincipal.getEmail());
        model.addAttribute("member", member);

        List<Product> products = productService.findAll();
        model.addAttribute("products", products);

        List<OrderDto> orders = orderService.findOrders(userPrincipal.getId());
        model.addAttribute("orders", orders);

        model.addAttribute("role", userPrincipal.getAuthorities());

        return "main";
    }
}
