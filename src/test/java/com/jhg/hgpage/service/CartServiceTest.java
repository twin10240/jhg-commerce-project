package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.service.CartService;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.dto.CartItemDto;
import com.jhg.hgpage.oms.repository.MemberRepository;
import com.jhg.hgpage.catalog.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장바구니 담기/조회 통합 테스트(임베디드 H2). 시드 상품의 하드코딩 ID 대신
 * 테스트가 만든 회원/상품만 사용하며, 트랜잭션 롤백으로 DB를 더럽히지 않는다.
 */
@SpringBootTest
@Transactional
class CartServiceTest {

    @Autowired CartService cartService;
    @Autowired MemberRepository memberRepository;
    @Autowired ProductRepository productRepository;

    private Member newMember() {
        return memberRepository.save(
                Member.createUser("장바구니테스터", "010-0000-0000", new Address("서울", "관악구", "500")));
    }

    private Product newProduct(String name, int price) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(price);
        return productRepository.save(product);
    }

    @Test
    void 장바구니에_담은_상품들이_단가와_라인합계로_조회된다() {
        Member member = newMember();
        Product a = newProduct("테스트상품A", 10000);
        Product b = newProduct("테스트상품B", 12000);

        cartService.addCartItem(member.getId(), a.getId(), 1);
        cartService.addCartItem(member.getId(), b.getId(), 3);

        List<CartItemDto> items = cartService.findCartItemByMemberId(member.getId());

        assertThat(items).hasSize(2);
        assertThat(items).extracting(CartItemDto::getUnitPrice).containsExactlyInAnyOrder(10000, 12000);
        assertThat(items).extracting(CartItemDto::getLineTotalPrice).containsExactlyInAnyOrder(10000, 36000);
        assertThat(items.stream().mapToInt(CartItemDto::getLineTotalPrice).sum()).isEqualTo(46000);
    }

    @Test
    void 같은_상품을_다시_담으면_수량만_증가한다() {
        Member member = newMember();
        Product a = newProduct("테스트상품A", 10000);

        cartService.addCartItem(member.getId(), a.getId(), 1);
        cartService.addCartItem(member.getId(), a.getId(), 2);

        List<CartItemDto> items = cartService.findCartItemByMemberId(member.getId());

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getQuantity()).isEqualTo(3);
        assertThat(items.get(0).getLineTotalPrice()).isEqualTo(30000);
    }
}
