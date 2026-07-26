package com.jhg.hgpage.oms.web.controller;

import com.jhg.hgpage.oms.dto.CartItemDto;
import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.oms.service.CartService;
import com.jhg.hgpage.contract.InventoryQueryPort;
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
    private final InventoryQueryPort inventoryQueryPort;

    @GetMapping("/cart")
    public String Cart(@AuthenticationPrincipal UserPrincipal user, Model model) {
        List<CartItemDto> cartItems = cartService.findCartItemByMemberId(user.getId());
        model.addAttribute("cartItems", cartItems);
        model.addAttribute("availability", inventoryQueryPort.availableByProductIds(
                cartItems.stream().map(CartItemDto::getProductId).toList()));

        int totalPrice = cartItems.stream().mapToInt(CartItemDto::getLineTotalPrice).sum();
        model.addAttribute("totalPrice", totalPrice);

        return "cart";
    }


}
