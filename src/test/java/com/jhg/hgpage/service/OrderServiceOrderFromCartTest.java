package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Inventory;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Order;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.exception.NotEnoughStockException;
import com.jhg.hgpage.repository.OrderRepository;
import com.jhg.hgpage.repository.OrderRepositoryQuery;
import com.jhg.hgpage.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceOrderFromCartTest {

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
    void 장바구니_주문이_성공하면_주문된_상품들이_장바구니에서_제거된다() {
        when(memberService.findMember(1L)).thenReturn(member());
        when(productRepository.findAllById(any())).thenReturn(List.of(
                productWithStock(1L, 10000, 10), productWithStock(2L, 20000, 10)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.orderFromCart(1L, ADDRESS, List.of(
                new OrderService.OrderLine(1L, 2),
                new OrderService.OrderLine(2L, 1)));

        verify(orderRepository).save(any(Order.class));
        verify(cartService).removeCartItems(1L, List.of(1L, 2L));
    }

    @Test
    void 재고가_부족해도_백오더로_접수되고_장바구니는_정리된다() {
        when(memberService.findMember(1L)).thenReturn(member());
        when(productRepository.findAllById(any())).thenReturn(List.of(productWithStock(1L, 10000, 0)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.orderFromCart(1L, ADDRESS, List.of(new OrderService.OrderLine(1L, 1)));

        verify(cartService).removeCartItems(1L, List.of(1L)); // 백오더도 정상 접수 — 장바구니 정리
    }

    @Test
    void 주문이_실패하면_장바구니를_건드리지_않는다() {
        when(memberService.findMember(1L)).thenReturn(member());
        when(productRepository.findAllById(any())).thenReturn(List.of()); // 없는 상품 → 진짜 실패

        assertThatThrownBy(() -> orderService.orderFromCart(1L, ADDRESS,
                List.of(new OrderService.OrderLine(99L, 1))))
                .isInstanceOf(com.jhg.hgpage.exception.EntityNotFoundException.class);

        verifyNoInteractions(cartService);
    }

    @Test
    void 일반_주문은_장바구니를_건드리지_않는다() {
        when(memberService.findMember(1L)).thenReturn(member());
        when(productRepository.findAllById(any())).thenReturn(List.of(productWithStock(1L, 10000, 10)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderService.order(1L, ADDRESS, List.of(new OrderService.OrderLine(1L, 1)));

        verifyNoInteractions(cartService);
    }
}
