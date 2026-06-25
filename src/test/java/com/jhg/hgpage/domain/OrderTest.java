package com.jhg.hgpage.domain;

import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 예약/백오더 모델의 주문 생명주기 — 도메인은 상태 전이/가드만 책임진다.
 * 재고 예약/판정/차감은 모두 서비스+InventoryPort(WMS)로 분리됐으므로
 * 도메인은 재고(Inventory)를 전혀 참조하지 않는다(구조적으로 결합 없음).
 */
class OrderTest {

    private Product product() {
        Product product = new Product();
        product.setName("상품");
        product.setPrice(10000);
        return product;
    }

    // 재고 예약/판정은 OrderAllocationService+InventoryService로 옮겨갔다(OrderAllocationServiceTest·InventoryServiceTest가 검증).
    // 도메인 단위 테스트는 상태 전이/가드만 다루므로, 할당 결과 상태를 직접 표시한다.
    private Order createOrderedOrder(Product product, int quantity) {
        Order order = newOrder(product, quantity);
        order.markOrdered();
        return order;
    }

    private Order createBackorder(Product product, int quantity) {
        Order order = newOrder(product, quantity);
        order.markBackordered();
        return order;
    }

    private Order newOrder(Product product, int quantity) {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        OrderItem orderItem = OrderItem.createOrderItem(product, product.getPrice(), quantity);
        return Order.createOrder(member, delivery, orderItem);
    }

    @Test
    void 주문을_생성하면_배송상태가_READY로_초기화된다() {
        Order order = createOrderedOrder(product(), 2);

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.READY);
    }

    // ── 취소 ───────────────────────────────────────────────────────

    @Test
    void ORDER_주문을_취소하면_CANCEL_상태가_된다() {
        Order order = createOrderedOrder(product(), 2); // ORDER 상태

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL);
    }

    @Test
    void BACKORDERED_주문도_취소할_수_있다() {
        Order order = createBackorder(product(), 5); // BACKORDERED, 예약 없음

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL);
    }

    @Test
    void 배송완료된_주문은_취소할_수_없다() {
        Order order = createOrderedOrder(product(), 2);
        order.completeDelivery(); // 배송완료(COMP)로 전이

        assertThatThrownBy(order::cancel)
                .isInstanceOf(IllegalStateException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER);
    }

    @Test
    void 이미_취소된_주문은_다시_취소할_수_없다() {
        Order order = createOrderedOrder(product(), 2);
        order.cancel(); // CANCEL로 전이

        assertThatThrownBy(order::cancel)
                .isInstanceOf(IllegalStateException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL); // 재취소 가드로 상태 불변
    }

    // ── 출고(배송완료) ─────────────────────────────────────────────

    @Test
    void 출고_처리하면_배송상태가_COMP가_된다() {
        Order order = createOrderedOrder(product(), 2); // ORDER 상태

        order.completeDelivery();

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.COMP);
    }

    @Test
    void BACKORDERED_주문은_출고할_수_없다() {
        Order order = createBackorder(product(), 5); // BACKORDERED

        assertThatThrownBy(order::completeDelivery)
                .isInstanceOf(IllegalStateException.class);

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.READY);
    }

    @Test
    void 취소된_주문은_배송완료_처리할_수_없다() {
        Order order = createOrderedOrder(product(), 2);
        order.cancel();

        assertThatThrownBy(order::completeDelivery)
                .isInstanceOf(IllegalStateException.class);

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.READY);
    }

    @Test
    void 이미_배송완료된_주문은_다시_처리할_수_없다() {
        Order order = createOrderedOrder(product(), 2);
        order.completeDelivery(); // 1회차: COMP로 전이

        assertThatThrownBy(order::completeDelivery)
                .isInstanceOf(IllegalStateException.class);

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.COMP); // 상태 불변(이중 처리 차단)
    }
}
