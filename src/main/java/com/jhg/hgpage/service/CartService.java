package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Cart;
import com.jhg.hgpage.domain.CartItem;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.domain.dto.view.CartItemDto;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.repository.CartRepository;
import com.jhg.hgpage.repository.CartRepositoryQuery;
import com.jhg.hgpage.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CartService {

    private final ProductRepository productRepository;
    private final CartRepository cartRepository;
    private final CartRepositoryQuery cartRepositoryQuery;

    @Transactional
    public Long addCartItem(Long memberId, Long productId, int quantity) {
        Product product = findProduct(productId);

        Cart cart = cartRepository.findCartByMemberId(memberId);
        CartItem cartItem = cart.addCartItem(product, quantity, product.getPrice());

        return cart.getId();
    }

    @Transactional
    public void updateCartItemQuantity(Long memberId, Long productId, int quantity) {
        Product product = findProduct(productId);
        Cart cart = cartRepository.findCartByMemberId(memberId);

        cart.changeItemQuantity(product, quantity);
    }

    @Transactional
    public void removeCartItem(Long memberId, Long productId) {
        Product product = findProduct(productId);
        Cart cart = cartRepository.findCartByMemberId(memberId);

        cart.removeItem(product);
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product", productId));
    }

    @Transactional
    public void removeCartItems(Long memberId, List<Long> productIds) {
        Cart cart = cartRepository.findCartByMemberId(memberId);
        cart.removeItems(productIds);
    }

    public Long getCartItemTotalCount(Long memberId) {
        return cartRepository.countCartItemByMemberId(memberId);
    }

    public List<CartItemDto> findCartItemByMemberId(Long memberId) {
        List<CartItemDto> cartItems = cartRepositoryQuery.findCartItemByMemberId(memberId);

        return IntStream.range(0, cartItems.size())
                .mapToObj(i -> {
                    CartItemDto ci = cartItems.get(i);
                    return CartItemDto.builder()
                            .memberId(ci.getMemberId())
                            .cartId(ci.getCartId())
                            .productId(ci.getProductId())
                            .idx(i +1)
                            .productName(ci.getProductName())
                            .cartPrice(ci.getLineTotalPrice())
                            .productPrice(ci.getUnitPrice())
                            .unitPrice(ci.getUnitPrice())
                            .lineTotalPrice(ci.getLineTotalPrice())
                            .quantity(ci.getQuantity())
                            .build();
                })
                .collect(Collectors.toList());
    }
}
