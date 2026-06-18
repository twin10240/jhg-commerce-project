package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.service.CartService;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Cart;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.repository.CartRepository;
import com.jhg.hgpage.oms.repository.CartRepositoryQuery;
import com.jhg.hgpage.catalog.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceRemoveItemsTest {

    @Mock ProductRepository productRepository;
    @Mock CartRepository cartRepository;
    @Mock CartRepositoryQuery cartRepositoryQuery;
    @InjectMocks CartService cartService;

    private Product product(long id, int price) {
        Product product = new Product();
        product.setId(id);
        product.setPrice(price);
        return product;
    }

    @Test
    void 여러_상품을_장바구니_한번_조회로_일괄_제거한다() {
        Cart cart = new Cart(Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500")));
        cart.addCartItem(product(1L, 10000), 1, 10000);
        cart.addCartItem(product(2L, 20000), 2, 20000);
        cart.addCartItem(product(3L, 30000), 3, 30000);
        when(cartRepository.findCartByMemberId(1L)).thenReturn(cart);

        cartService.removeCartItems(1L, List.of(1L, 3L));

        assertThat(cart.getCartItems()).hasSize(1);
        assertThat(cart.getCartItems().get(0).getProduct().getId()).isEqualTo(2L);
        // 장바구니는 1번만 조회하고, 상품 테이블은 아예 조회하지 않는다
        verify(cartRepository, times(1)).findCartByMemberId(1L);
        verifyNoInteractions(productRepository);
    }
}
