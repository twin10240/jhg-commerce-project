package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Address;
import com.jhg.hgpage.domain.Delivery;
import com.jhg.hgpage.domain.Inventory;
import com.jhg.hgpage.domain.Member;
import com.jhg.hgpage.domain.Order;
import com.jhg.hgpage.domain.OrderItem;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.domain.dto.view.OrderDto;
import com.jhg.hgpage.domain.enums.OrderStatus;
import com.jhg.hgpage.repository.OrderRepository;
import com.jhg.hgpage.repository.OrderRepositoryQuery;
import com.jhg.hgpage.catalog.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

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

    private Product productWithStock(int price, int stock) {
        Product product = new Product();
        product.setPrice(price);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(stock);
        product.setInventory(inventory);
        return product;
    }

    private Order orderWith(long id, int price, int quantity) {
        Member member = Member.createUser("테스터", "010-0000-0000", ADDRESS);
        Delivery delivery = new Delivery();
        delivery.setAddress(ADDRESS);
        Order order = Order.createOrder(member, delivery,
                OrderItem.createOrderItem(productWithStock(price, quantity), price, quantity));
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
    }

    @Test
    void 주문이_없으면_빈_목록을_반환한다() {
        when(orderRepositoryQuery.findOrders(1L)).thenReturn(List.of());

        assertThat(orderService.findOrders(1L)).isEmpty();
    }
}
