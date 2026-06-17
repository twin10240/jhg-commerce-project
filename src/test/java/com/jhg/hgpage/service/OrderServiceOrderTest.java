package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Inventory;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Order;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.repository.OrderRepository;
import com.jhg.hgpage.repository.OrderRepositoryQuery;
import com.jhg.hgpage.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * order()의 상품 조회 전략 — 라인별 findById 루프(N+1)가 아니라 findAllById로 한 번에 일괄 조회한다(#9 ①).
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceOrderTest {

    @Mock MemberService memberService;
    @Mock ProductRepository productRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderRepositoryQuery orderRepositoryQuery;
    @Mock CartService cartService;
    @Mock BackorderAllocator backorderAllocator;
    @Mock OrderAllocationService orderAllocationService;
    @InjectMocks OrderService orderService;

    private static final Address ADDRESS = new Address("서울", "관악구", "500");

    private Member member() {
        return Member.createUser("테스터", "010-0000-0000", ADDRESS);
    }

    private Product productWithStock(long id, int price, int stock) {
        Product product = new Product();
        product.setId(id);
        product.setPrice(price);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(stock);
        product.setInventory(inventory);
        return product;
    }

    @Test
    @SuppressWarnings("unchecked")
    void 여러_상품_주문시_상품을_한_번의_findAllById로_일괄_조회한다() {
        when(memberService.findMember(1L)).thenReturn(member());
        when(productRepository.findAllById(any())).thenReturn(List.of(
                productWithStock(10L, 10000, 100),
                productWithStock(11L, 20000, 100)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.order(1L, ADDRESS, List.of(
                new OrderService.OrderLine(10L, 1),
                new OrderService.OrderLine(11L, 2)));

        ArgumentCaptor<Iterable<Long>> idsCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(productRepository).findAllById(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(10L, 11L);
        // 라인별 단건 조회(N+1)는 더 이상 사용하지 않는다
        verify(productRepository, never()).findById(any());
    }

    @Test
    void 주문된_상품이_존재하지_않으면_EntityNotFoundException() {
        when(memberService.findMember(1L)).thenReturn(member());
        when(productRepository.findAllById(any())).thenReturn(List.of()); // 없는 상품

        assertThatThrownBy(() -> orderService.order(1L, ADDRESS,
                List.of(new OrderService.OrderLine(99L, 1))))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
