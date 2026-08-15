package com.jhg.hgpage.domain;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderPaymentStateTest {

    @Test
    void 출고는_재고확보_ORDER_상태에서만_허용한다() {
        Order order = order();
        order.markPaymentPending();

        assertThatThrownBy(order::ship)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 할당처리중_취소는_해제여부를_나중에_결정한다() {
        Order order = order();
        order.markPaymentPending();
        order.markAllocationPending();
        order.claimAllocation(LocalDateTime.now());

        order.requestCancellation(null, LocalDateTime.now());
        order.resolveCancellationRelease(true);
        order.finishCancellation();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL);
        assertThat(order.getCancellationReleaseRequired()).isTrue();
    }

    private Order order() {
        Product product = new Product();
        product.setPrice(10_000);
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        return Order.createOrder(member, delivery, OrderItem.createOrderItem(product, 10_000, 1));
    }
}
