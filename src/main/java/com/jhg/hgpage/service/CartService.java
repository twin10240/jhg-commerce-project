package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Cart;
import com.jhg.hgpage.domain.CartItem;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.domain.dto.CartItemDto;
import com.jhg.hgpage.repositoey.CartRepository;
import com.jhg.hgpage.repositoey.CartRepositoryQuery;
import com.jhg.hgpage.repositoey.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
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
        Product product = productRepository.findById(productId).get();

        Cart cart = cartRepository.findCartByMemberId(memberId);
        CartItem cartItem = cart.addCartItem(product, quantity, product.getPrice());

        return cart.getId();
    }

    private <T> T firstOrElseGet(List<T> list, Supplier<T> supplier) {
        return list.isEmpty() ? supplier.get() : list.get(0);
    }

    public Long getCartItemTotalCount(Long memberId) {
        return cartRepository.countCartItemByMemberId(memberId);
    }

    public List<CartItemDto> findCartItemByMemberId(Long memberId) {
        List<CartItemDto> cartItems = cartRepositoryQuery.findCartItemByMemberId(memberId);

//        AtomicInteger rowNum = new AtomicInteger(1);
//        return cartItems.stream()
//                .map(ci -> new CartItemDto(ci.getMemerId(), ci.getCartId(), ci.getProductId(), rowNum.getAndIncrement(), ci.getProductName(), ci.getTotalPice(), ci.getQuantity()))
//                .collect(Collectors.toList());

        return IntStream.range(0, cartItems.size())
                .mapToObj(i -> {
                    CartItemDto ci = cartItems.get(i);
                    return new CartItemDto(
                            ci.getMemberId(),
                            ci.getCartId(),
                            ci.getProductId(),
                            i + 1,
                            ci.getProductName(),
                            ci.getTotalPrice(),
                            ci.getProductPrice(),
                            ci.getQuantity()
                    );
                })
                .collect(Collectors.toList());
    }
}
