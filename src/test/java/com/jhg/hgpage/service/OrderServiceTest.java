package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.service.BackorderAllocator;
import com.jhg.hgpage.oms.service.CartService;
import com.jhg.hgpage.oms.service.MemberService;
import com.jhg.hgpage.oms.service.OrderService;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.dto.OrderDto;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import com.jhg.hgpage.catalog.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 회원의 주문 목록 조회 — 조회된 Order를 OrderDto(id/status/총액/주문일)로 매핑해 반환한다.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock MemberService memberService;
    @Mock ProductRepository productRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderRepositoryQuery orderRepositoryQuery;
    @Mock CartService cartService;
    @Mock BackorderAllocator backorderAllocator;
    @InjectMocks OrderService orderService;

    private static final Address ADDRESS = new Address("서울", "관악구", "500");

    private Product productOf(int price) {
        Product product = new Product();
        product.setPrice(price);
        return product;
    }

    private Order orderWith(long id, int price, int quantity) {
        Member member = Member.createUser("테스터", "010-0000-0000", ADDRESS);
        Delivery delivery = new Delivery();
        delivery.setAddress(ADDRESS);
        Order order = Order.createOrder(member, delivery,
                OrderItem.createOrderItem(productOf(price), price, quantity));
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }

    @Test
    void 회원의_주문목록을_OrderDto로_매핑해_반환한다() {
        Order o1 = orderWith(10L, 10000, 2); // 총액 20000
        Order o2 = orderWith(11L, 5000, 1);  // 총액 5000
        when(orderRepositoryQuery.findOrders(1L)).thenReturn(List.of(o1, o2));

        List<OrderDto> result = orderService.findOrders(1L);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(OrderDto::getId).containsExactly(10L, 11L);
        assertThat(result).extracting(OrderDto::getTotalAmount).containsExactly(20000, 5000);
        assertThat(result).extracting(OrderDto::getStatus)
                .containsExactly(OrderStatus.ORDER, OrderStatus.ORDER);
        assertThat(result).extracting(OrderDto::getDeliveryStatus)
                .containsOnly(com.jhg.hgpage.oms.domain.enums.DeliveryStatus.READY);
    }

    @Test
    void 주문이_없으면_빈_목록을_반환한다() {
        when(orderRepositoryQuery.findOrders(1L)).thenReturn(List.of());

        assertThat(orderService.findOrders(1L)).isEmpty();
    }

    @Test
    void 백오더의_상품별_대기수량을_합산한다() {
        Product product = productOf(10000);
        ReflectionTestUtils.setField(product, "id", 3L);
        Member member = Member.createUser("테스터", "010-0000-0000", ADDRESS);

        Delivery firstDelivery = new Delivery();
        firstDelivery.setAddress(ADDRESS);
        Order first = Order.createOrder(member, firstDelivery,
                OrderItem.createOrderItem(product, 10000, 2));
        first.markBackordered();

        Delivery secondDelivery = new Delivery();
        secondDelivery.setAddress(ADDRESS);
        Order second = Order.createOrder(member, secondDelivery,
                OrderItem.createOrderItem(product, 10000, 3));
        second.markBackordered();
        when(orderRepositoryQuery.findBackordersContaining(List.of(3L))).thenReturn(List.of(first, second));

        assertThat(orderService.backorderDemandByProductId(List.of(3L)))
                .isEqualTo(Map.of(3L, 5));
    }
}
