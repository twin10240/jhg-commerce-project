package com.jhg.hgpage.api;

import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.service.CartService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartApiController {

    private final CartService cartService;

    @Data
    @AllArgsConstructor
    static class CartItemsTotalCountResponse {
        private long count;
    }

    @Data
    static class CartItem {
        private long productId;
        private int qty;
    }

    private CartItemsTotalCountResponse buildCartCountResponse(Long memberId) {
        Long count = cartService.getCartItemTotalCount(memberId);
        return new CartItemsTotalCountResponse(count);
    }

    @GetMapping("/count")
    public CartItemsTotalCountResponse getCartCount(@AuthenticationPrincipal UserPrincipal user) {
        return buildCartCountResponse(user.getId());
    }

    @PostMapping("/items")
    public CartItemsTotalCountResponse addCartItem(@AuthenticationPrincipal UserPrincipal user, @RequestBody CartItem req) {
        Long cartItemId = cartService.addCartItem(user.getId(), req.getProductId(), req.getQty());

        return buildCartCountResponse(user.getId());
    }
}
