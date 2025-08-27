package com.jhg.hgpage.controller.order;

import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Order;
import com.jhg.hgpage.service.MemberService;
import com.jhg.hgpage.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class OrderController {

    private final MemberService memberService;
    private final OrderService orderService;

    @PostMapping("/orders")
    public String createOrder(@AuthenticationPrincipal(expression = "id") Long userId, @RequestParam("product_id") Long product_id, @RequestParam("qty") int qty,@RequestParam("price") int price) {
        Member member = memberService.findById(userId);

//        Order order = Order.createOrder(member);

        return "redirect:/main";
    }
}
