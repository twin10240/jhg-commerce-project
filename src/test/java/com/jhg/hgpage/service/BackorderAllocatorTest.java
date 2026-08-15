package com.jhg.hgpage.service;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import com.jhg.hgpage.oms.service.BackorderAllocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackorderAllocatorTest {

    @Mock OrderRepositoryQuery orderRepositoryQuery;

    @Test
    void 입고된상품의_유료백오더를_FIFO로_비동기할당대기에_넣는다() {
        Order older = backorder(10L);
        Order newer = backorder(20L);
        when(orderRepositoryQuery.findPaidBackordersContaining(List.of(1L))).thenReturn(List.of(older, newer));
        BackorderAllocator allocator = new BackorderAllocator(orderRepositoryQuery);

        int enqueued = allocator.allocate(List.of(1L));

        assertThat(enqueued).isEqualTo(2);
        assertThat(List.of(older.getStatus(), newer.getStatus()))
                .containsExactly(OrderStatus.ALLOCATION_PENDING, OrderStatus.ALLOCATION_PENDING);
        assertThat(older.getNextAllocationAttemptAt()).isNotNull();
        assertThat(newer.getNextAllocationAttemptAt()).isNotNull();
    }

    private Order backorder(long id) {
        Product product = new Product();
        product.setId(1L);
        product.setPrice(10_000);
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery,
                OrderItem.createOrderItem(product, product.getPrice(), 1));
        ReflectionTestUtils.setField(order, "id", id);
        order.markBackordered();
        return order;
    }
}
