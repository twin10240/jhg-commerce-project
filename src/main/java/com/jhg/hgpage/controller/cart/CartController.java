package com.jhg.hgpage.controller.cart;

import com.jhg.hgpage.domain.dto.view.CartItemDto;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping("/cart")
    public String Cart(@AuthenticationPrincipal UserPrincipal user, Model model) {
        List<CartItemDto> cartItems = cartService.findCartItemByMemberId(user.getId());
        model.addAttribute("cartItems", cartItems);

        int totalPrice = cartItems.stream().mapToInt(ci -> ci.getCartPrice()).sum();
        model.addAttribute("totalPrice", totalPrice);

        return "cart";
    }


}
