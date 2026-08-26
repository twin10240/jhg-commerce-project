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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerReturnTest {

    @Test
    void 새_반품은_OMS_승인대기로_생성한다() {
        CustomerReturn result = pendingReturn(2);

        assertThat(result.getStatus()).isEqualTo(CustomerReturnStatus.PENDING_APPROVAL);
    }

    @Test
    void 승인하면_WMS_전송대기가_되고_승인자를_기록한다() {
        CustomerReturn result = pendingReturn(2);

        result.approve(" admin@example.com ");

        assertThat(result.getStatus()).isEqualTo(CustomerReturnStatus.PENDING_SUBMISSION);
        assertThat(result.getReviewedBy()).isEqualTo("admin@example.com");
        assertThat(result.getReviewedAt()).isNotNull();
    }

    @Test
    void 반려하면_사유를_기록하고_재처리를_거부한다() {
        CustomerReturn result = pendingReturn(2);

        result.reject("admin@example.com", " 정책상 반품 불가 ");

        assertThat(result.getStatus()).isEqualTo(CustomerReturnStatus.REJECTED);
        assertThat(result.getRejectionReason()).isEqualTo("정책상 반품 불가");
        assertThatThrownBy(() -> result.approve("admin@example.com"))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidRejectionReasons")
    void 반려사유는_비어있거나_500자를_초과할수없다(String reason) {
        CustomerReturn result = pendingReturn(2);

        assertThatThrownBy(() -> result.reject("admin@example.com", reason))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(result.getStatus()).isEqualTo(CustomerReturnStatus.PENDING_APPROVAL);
        assertThat(result.getReviewedBy()).isNull();
        assertThat(result.getReviewedAt()).isNull();
        assertThat(result.getRejectionReason()).isNull();
    }

    @Test
    void 승인자는_비어있을수없다() {
        CustomerReturn result = pendingReturn(2);

        assertThatThrownBy(() -> result.approve(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 승인0은_REJECTED만_허용한다() {
        CustomerReturn result = pendingSubmissionReturn(2);
        Long orderItemId = result.getItems().get(0).getOrderItem().getId();

        assertThatThrownBy(() -> result.complete(List.of(new CustomerReturn.ResultItem(
                orderItemId, 0, ReturnDisposition.RESTOCKED))))
                .isInstanceOf(IllegalArgumentException.class);

        result.complete(List.of(new CustomerReturn.ResultItem(orderItemId, 0, ReturnDisposition.REJECTED)));
    }

    @Test
    void 승인수량이_있으면_RESTOCKED나_DISPOSED만_허용한다() {
        CustomerReturn rejected = pendingSubmissionReturn(2);
        Long rejectedOrderItemId = rejected.getItems().get(0).getOrderItem().getId();
        CustomerReturn exceeded = pendingSubmissionReturn(2);
        Long exceededOrderItemId = exceeded.getItems().get(0).getOrderItem().getId();

        assertThatThrownBy(() -> rejected.complete(List.of(new CustomerReturn.ResultItem(
                rejectedOrderItemId, 1, ReturnDisposition.REJECTED))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> exceeded.complete(List.of(new CustomerReturn.ResultItem(
                exceededOrderItemId, 3, ReturnDisposition.RESTOCKED))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 최종상태는_변경할수없다() {
        Fixture fixture = deliveredOrder();
        CustomerReturn result = CustomerReturn.create(fixture.order(), UUID.randomUUID(), "불량",
                List.of(new CustomerReturn.RequestItem(fixture.orderItem(), 2)));
        result.approve("admin@example.com");
        result.markRequested(1L);

        result.complete(List.of(new CustomerReturn.ResultItem(
                fixture.orderItem().getId(), 1, ReturnDisposition.RESTOCKED)));

        assertThatThrownBy(result::markReceived).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 완료된_반품의_품목목록은_외부에서_변경할수없다() {
        CustomerReturn result = completedReturn();
        CustomerReturnItem item = result.getItems().get(0);

        assertThatThrownBy(() -> result.getItems().clear()).isInstanceOf(UnsupportedOperationException.class);

        assertThat(item.getAcceptedQuantity()).isEqualTo(1);
        assertThat(item.getDisposition()).isEqualTo(ReturnDisposition.RESTOCKED);
    }

    @Test
    void 반품품목의_결과변경은_공개_API로_노출하지않는다() {
        assertThatThrownBy(() -> CustomerReturnItem.class.getMethod("applyResult", int.class, ReturnDisposition.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void 잘못된_나중_결과가_있으면_어떤_품목도_변경하지않는다() {
        Fixture fixture = deliveredOrder(2, 2);
        CustomerReturn result = CustomerReturn.create(fixture.order(), UUID.randomUUID(), "불량",
                fixture.orderItems().stream().map(item -> new CustomerReturn.RequestItem(item, 2)).toList());
        result.approve("admin@example.com");
        result.markRequested(1L);

        assertThatThrownBy(() -> result.complete(List.of(
                new CustomerReturn.ResultItem(fixture.orderItems().get(0).getId(), 1, ReturnDisposition.RESTOCKED),
                new CustomerReturn.ResultItem(fixture.orderItems().get(1).getId(), 3, ReturnDisposition.RESTOCKED))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(result.getStatus()).isEqualTo(CustomerReturnStatus.REQUESTED);
        assertThat(result.getItems()).extracting(CustomerReturnItem::getAcceptedQuantity)
                .containsOnly(null, null);
        assertThat(result.getItems()).extracting(CustomerReturnItem::getDisposition)
                .containsOnly(null, null);
    }

    private CustomerReturn pendingReturn(int quantity) {
        Fixture fixture = deliveredOrder();
        return CustomerReturn.create(fixture.order(), UUID.randomUUID(), "불량",
                List.of(new CustomerReturn.RequestItem(fixture.orderItem(), quantity)));
    }

    private CustomerReturn pendingSubmissionReturn(int quantity) {
        CustomerReturn customerReturn = pendingReturn(quantity);
        customerReturn.approve("admin@example.com");
        return customerReturn;
    }

    private Fixture deliveredOrder() {
        return deliveredOrder(2);
    }

    private Fixture deliveredOrder(int... quantities) {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        List<OrderItem> orderItems = new ArrayList<>();
        for (int index = 0; index < quantities.length; index++) {
            Product product = new Product();
            product.setName("상품" + index);
            product.setPrice(10000);
            OrderItem orderItem = OrderItem.createOrderItem(product, product.getPrice(), quantities[index]);
            orderItem.setId((long) index + 1);
            orderItems.add(orderItem);
        }
        Order order = Order.createOrder(member, delivery, orderItems.toArray(new OrderItem[0]));
        order.ship();
        order.deliver();
        return new Fixture(order, orderItems);
    }

    private CustomerReturn completedReturn() {
        CustomerReturn result = pendingSubmissionReturn(2);
        result.markRequested(1L);
        result.complete(List.of(new CustomerReturn.ResultItem(
                result.getItems().get(0).getOrderItem().getId(), 1, ReturnDisposition.RESTOCKED)));
        return result;
    }

    private record Fixture(Order order, List<OrderItem> orderItems) {
        private OrderItem orderItem() {
            return orderItems.get(0);
        }
    }

    private static Stream<Arguments> invalidRejectionReasons() {
        return Stream.of(Arguments.of((String) null), Arguments.of(""), Arguments.of("   "),
                Arguments.of("x".repeat(501)));
    }
}
