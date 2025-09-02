package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Cart;
import com.jhg.hgpage.domain.CartItem;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.repositoey.CartRepository;
import com.jhg.hgpage.repositoey.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        CartItem cartItem = CartItem.createCartItem(product, product.getPrice(), quantity);

        Cart cart = Cart.createCart(member, cartItem);
        cartRepository.save(cart);

        return cartItem.getId();
    }

    public Long getCartTotalCount(Long memberId) {
        return cartRepository.CartCountWithQueryDsl(memberId);
    }
}
