package com.jhg.hgpage.service;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.catalog.ProductRepository;
import com.jhg.hgpage.contract.InventoryPort;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.domain.enums.PaymentStatus;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.repository.PaymentRepository;
import com.jhg.hgpage.oms.service.CartService;
import com.jhg.hgpage.oms.service.CheckoutService;
import com.jhg.hgpage.oms.service.MemberService;
import com.jhg.hgpage.oms.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

    @Mock MemberService memberService;
    @Mock ProductRepository productRepository;
    @Mock OrderRepository orderRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock CartService cartService;
    @Mock InventoryPort inventoryPort;

    CheckoutService checkoutService;

    @BeforeEach
    void setUp() {
        checkoutService = new CheckoutService(memberService, productRepository, orderRepository,
                paymentRepository, cartService);
        when(memberService.findMember(1L)).thenReturn(
                Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500")));
    }

    private void stubSuccessfulPersistence() {
        Product product = new Product();
        product.setId(7L);
        product.setPrice(10_000);
        stubSuccessfulPersistence(List.of(product));
    }

    private void stubSuccessfulPersistence(List<Product> products) {
        when(productRepository.findAllById(any())).thenReturn(products);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 10L);
            return order;
        });
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            ReflectionTestUtils.setField(payment, "id", 20L);
            return payment;
        });
    }

    @Test
    void 주문과_결제만_저장하고_고객승인전에는_결제시도를_만들지_않는다() {
        stubSuccessfulPersistence();
        CheckoutService.CheckoutResult result = checkoutService.createPending(1L,
                new Address("서울", "관악구", "500"), List.of(new OrderService.OrderLine(7L, 2)), false);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(orderRepository).save(orderCaptor.capture());
        verify(paymentRepository).save(paymentCaptor.capture());

        assertThat(result.orderId()).isEqualTo(10L);
        assertThat(orderCaptor.getValue().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentCaptor.getValue().getOrderAmount()).isEqualTo(20_000);
        verifyNoInteractions(inventoryPort);
    }

    @Test
    void 장바구니_주문은_같은_트랜잭션에서_선택상품을_정리한다() {
        stubSuccessfulPersistence();
        checkoutService.createPending(1L, new Address("서울", "관악구", "500"),
                List.of(new OrderService.OrderLine(7L, 2)), true);

        verify(cartService).removeCartItems(1L, List.of(7L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 여러_상품은_findAllById_한번으로_일괄_조회한다() {
        Product first = new Product();
        first.setId(7L);
        first.setPrice(10_000);
        Product second = new Product();
        second.setId(8L);
        second.setPrice(20_000);
        stubSuccessfulPersistence(List.of(first, second));

        checkoutService.createPending(1L, new Address("서울", "관악구", "500"), List.of(
                new OrderService.OrderLine(7L, 1),
                new OrderService.OrderLine(8L, 2)), false);

        ArgumentCaptor<Iterable<Long>> ids = ArgumentCaptor.forClass(Iterable.class);
        verify(productRepository).findAllById(ids.capture());
        assertThat(ids.getValue()).containsExactly(7L, 8L);
        verify(productRepository, never()).findById(any());
    }

    @Test
    void 바로구매는_장바구니를_변경하지_않는다() {
        stubSuccessfulPersistence();

        checkoutService.createPending(1L, new Address("서울", "관악구", "500"),
                List.of(new OrderService.OrderLine(7L, 2)), false);

        verifyNoInteractions(cartService);
    }

    @Test
    void 없는_상품이면_주문과_장바구니를_변경하지_않는다() {
        when(productRepository.findAllById(any())).thenReturn(List.of());

        assertThatThrownBy(() -> checkoutService.createPending(1L,
                new Address("서울", "관악구", "500"), List.of(new OrderService.OrderLine(99L, 1)), true))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("99");

        verifyNoInteractions(cartService);
    }
}
