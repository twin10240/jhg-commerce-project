package com.jhg.hgpage.api;

import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.repositoey.CartRepository;
import com.jhg.hgpage.service.CartService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartApiController {

    private final CartService cartService;

    @Data
    @AllArgsConstructor
    public static class ResltForm<T> {
        private long count;

        private T data;
    }

    @Data
    @AllArgsConstructor
    static class CartTotalCountResponse {
        private long count;
    }

    @Data
    static class CartItem {
        private long productId;
        private int qty;
    }

    @GetMapping("/count")
    public CartTotalCountResponse getCartCount(@AuthenticationPrincipal UserPrincipal user) {
        Long cartTotalCount = cartService.getCartTotalCount(user.getId());

        return new CartTotalCountResponse(cartTotalCount);
    }

    @PostMapping("/items")
//    public ResltForm addCartItem(@AuthenticationPrincipal UserPrincipal user, @RequestParam("productId") Long productId, @RequestParam("qty") int quantity) {
    public ResltForm addCartItem(@AuthenticationPrincipal UserPrincipal user, @RequestBody CartItem req) {
        Long cartItemId = cartService.addCartItem(user.getId(), req.getProductId(), req.getQty());
        Long cartTotalCount = cartService.getCartTotalCount(user.getId());

        return new ResltForm<>(cartTotalCount, cartTotalCount);
    }
}
