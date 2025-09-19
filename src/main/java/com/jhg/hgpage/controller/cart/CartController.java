package com.jhg.hgpage.controller.cart;

import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping("/Cart")
    public String Cart(@AuthenticationPrincipal UserPrincipal userPrincipal) {

        return "cart";
    }
}
