package com.jhg.hgpage.oms.web.api;

import com.jhg.hgpage.domain.dto.UserPrincipal;
import com.jhg.hgpage.oms.service.CartService;
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
    static class CartItemsTotalCountResponse {
        private long count;
    }

    @Data
    static class CartItem {
        private long productId;
        private int qty;
    }

    @Data
    static class CartItemQuantity {
        private int qty;
    }

    @Data
    static class CartItemsDeleteRequest {
        private List<Long> productIds;
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

    @PatchMapping("/items/{productId}")
    public CartItemsTotalCountResponse updateCartItemQuantity(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long productId,
            @RequestBody CartItemQuantity req
    ) {
        cartService.updateCartItemQuantity(user.getId(), productId, req.getQty());

        return buildCartCountResponse(user.getId());
    }

    @DeleteMapping("/items/{productId}")
    public CartItemsTotalCountResponse removeCartItem(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long productId
    ) {
        cartService.removeCartItem(user.getId(), productId);

        return buildCartCountResponse(user.getId());
    }

    @DeleteMapping("/items")
    public CartItemsTotalCountResponse removeCartItems(
            @AuthenticationPrincipal UserPrincipal user,
            @RequestBody CartItemsDeleteRequest req
    ) {
        cartService.removeCartItems(user.getId(), req.getProductIds());

        return buildCartCountResponse(user.getId());
    }
}
