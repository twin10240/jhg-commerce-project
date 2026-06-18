package com.jhg.hgpage.domain;

import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Cart;
import com.jhg.hgpage.catalog.Product;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CartTest {

    private Product product(long id, String name, int price) {
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setPrice(price);
        return product;
    }

    private Cart cartWithProducts(Product... products) {
        Cart cart = new Cart(Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500")));
        for (Product product : products) {
            cart.addCartItem(product, 1, product.getPrice());
        }
        return cart;
    }

    @Test
    void 지정한_상품들만_장바구니에서_제거된다() {
        Cart cart = cartWithProducts(
                product(1L, "상품1", 10000),
                product(2L, "상품2", 20000),
                product(3L, "상품3", 30000));

        cart.removeItems(List.of(1L, 3L));

        assertThat(cart.getCartItems()).hasSize(1);
        assertThat(cart.getCartItems().get(0).getProduct().getId()).isEqualTo(2L);
    }

    @Test
    void 장바구니에_없는_상품_id가_섞여_있어도_있는_것만_제거된다() {
        Cart cart = cartWithProducts(
                product(1L, "상품1", 10000),
                product(2L, "상품2", 20000));

        cart.removeItems(List.of(2L, 99L));

        assertThat(cart.getCartItems()).hasSize(1);
        assertThat(cart.getCartItems().get(0).getProduct().getId()).isEqualTo(1L);
    }

    @Test
    void 빈_id_목록이면_아무것도_제거하지_않는다() {
        Cart cart = cartWithProducts(product(1L, "상품1", 10000));

        cart.removeItems(List.of());

        assertThat(cart.getCartItems()).hasSize(1);
    }
}
