package com.jhg.hgpage.domain;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.CustomerReturnItem;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.domain.enums.ReturnDisposition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerReturnTest {

    @Test
    void 배송완료_주문의_반품요청을_PENDING으로_생성한다() {
        Fixture fixture = deliveredOrder();

        CustomerReturn result = CustomerReturn.create(fixture.order(), UUID.randomUUID(), "불량",
                List.of(new CustomerReturn.RequestItem(fixture.orderItem(), 2)));

        assertThat(result.getStatus()).isEqualTo(CustomerReturnStatus.PENDING_SUBMISSION);
        assertThat(result.getItems()).singleElement()
                .extracting(CustomerReturnItem::getRequestedQuantity).isEqualTo(2);
    }

    @Test
    void 승인0은_REJECTED만_허용한다() {
        CustomerReturnItem item = pendingItem(2);

        assertThatThrownBy(() -> item.applyResult(0, ReturnDisposition.RESTOCKED))
                .isInstanceOf(IllegalArgumentException.class);

        item.applyResult(0, ReturnDisposition.REJECTED);
    }

    @Test
    void 승인수량이_있으면_RESTOCKED나_DISPOSED만_허용한다() {
        CustomerReturnItem item = pendingItem(2);

        assertThatThrownBy(() -> item.applyResult(1, ReturnDisposition.REJECTED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item.applyResult(3, ReturnDisposition.RESTOCKED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 최종상태는_변경할수없다() {
        Fixture fixture = deliveredOrder();
        CustomerReturn result = CustomerReturn.create(fixture.order(), UUID.randomUUID(), "불량",
                List.of(new CustomerReturn.RequestItem(fixture.orderItem(), 2)));
        result.markRequested(1L);

        result.complete(List.of(new CustomerReturn.ResultItem(
                fixture.orderItem().getId(), 1, ReturnDisposition.RESTOCKED)));

        assertThatThrownBy(result::markReceived).isInstanceOf(IllegalStateException.class);
    }

    private CustomerReturnItem pendingItem(int quantity) {
        Fixture fixture = deliveredOrder();
        return CustomerReturn.create(fixture.order(), UUID.randomUUID(), "불량",
                List.of(new CustomerReturn.RequestItem(fixture.orderItem(), quantity))).getItems().get(0);
    }

    private Fixture deliveredOrder() {
        Product product = new Product();
        product.setName("상품");
        product.setPrice(10000);
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        OrderItem orderItem = OrderItem.createOrderItem(product, product.getPrice(), 2);
        orderItem.setId(1L);
        Order order = Order.createOrder(member, delivery, orderItem);
        order.ship();
        order.deliver();
        return new Fixture(order, orderItem);
    }

    private record Fixture(Order order, OrderItem orderItem) {}
}
