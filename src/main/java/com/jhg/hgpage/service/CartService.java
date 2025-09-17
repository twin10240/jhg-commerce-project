package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Cart;
import com.jhg.hgpage.domain.CartItem;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.repositoey.CartRepository;
import com.jhg.hgpage.repositoey.CartRepositoryQuery;
import com.jhg.hgpage.repositoey.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CartService {

    private final ProductRepository productRepository;
    private final MemberService memberService;
    private final CartRepository cartRepository;

    @Transactional
    public Long addCartItem(Long memberId, Long productId, int quantity) {
        Member member = memberService.findById(memberId);

        Product product = productRepository.findById(productId).get();

        Cart cart = firstOrElseGet(cartRepository.findCartByMemberId(memberId), () -> Cart.createCart(member));
        CartItem cartItem = cart.addCartItem(product, quantity, product.getPrice());

        return cart.getId();
    }

    private <T> T firstOrElseGet(List<T> list, Supplier<T> supplier) {
        return list.isEmpty() ? supplier.get() : list.get(0);
    }

    public Long getCartTotalCount(Long memberId) {
        return cartRepository.countCartByMemberId(memberId);
    }

    public Long getCartItemTotalCount(Long memberId) {
        return cartRepository.countCartItemByMemberId(memberId);
    }
}
