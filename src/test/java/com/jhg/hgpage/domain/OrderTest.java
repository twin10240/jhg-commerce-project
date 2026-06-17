package com.jhg.hgpage.domain;

import com.jhg.hgpage.domain.enums.DeliveryStatus;
import com.jhg.hgpage.domain.enums.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 예약/백오더 모델의 주문 생명주기.
 * 주문 = 예약(가용분 있을 때) 또는 백오더 접수(부족할 때, 거부하지 않음).
 * 실물 차감은 출고(completeDelivery) 시점.
 */
class OrderTest {

    private Product productWithStock(int stock) {
        Product product = new Product();
        product.setName("상품");
        product.setPrice(10000);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(stock);
        product.setInventory(inventory);
        return product;
    }

    private Order createAllocatedOrder(Product product, int quantity) {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        OrderItem orderItem = OrderItem.createOrderItem(product, product.getPrice(), quantity);
        Order order = Order.createOrder(member, delivery, orderItem);
        order.allocate();
        return order;
    }

    @Test
    void 주문을_생성하면_배송상태가_READY로_초기화된다() {
        Order order = createAllocatedOrder(productWithStock(10), 2);

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.READY);
    }

    // ── 할당(allocate): 예약 or 백오더 ─────────────────────────────

    @Test
    void 가용_재고가_있으면_예약되고_ORDER_상태다() {
        Product product = productWithStock(10);

        Order order = createAllocatedOrder(product, 2);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER);
        assertThat(product.getInventory().getReservedQty()).isEqualTo(2);
        assertThat(product.getInventory().getOnHandQty()).isEqualTo(10); // 실물은 불변
    }

    @Test
    void 가용_재고가_부족하면_거부하지_않고_BACKORDERED로_접수된다() {
        Product product = productWithStock(1);

        Order order = createAllocatedOrder(product, 5);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
        assertThat(product.getInventory().getReservedQty()).isEqualTo(0); // 예약 없음
    }

    @Test
    void 여러_상품_중_하나라도_부족하면_아무것도_예약하지_않는다() {
        Product enough = productWithStock(10);
        Product short_ = productWithStock(1);
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        Order order = Order.createOrder(member, delivery,
                OrderItem.createOrderItem(enough, enough.getPrice(), 2),
                OrderItem.createOrderItem(short_, short_.getPrice(), 5));

        order.allocate();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
        assertThat(enough.getInventory().getReservedQty()).isEqualTo(0); // 가용 라인도 미예약
        assertThat(short_.getInventory().getReservedQty()).isEqualTo(0);
    }

    @Test
    void 백오더_주문은_재할당으로_가용해지면_예약되고_ORDER로_승격된다() {
        Product product = productWithStock(1);
        Order order = createAllocatedOrder(product, 5); // BACKORDERED
        product.getInventory().addOnHandQty(10); // 입고

        order.allocate();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER);
        assertThat(product.getInventory().getReservedQty()).isEqualTo(5);
    }

    // ── 취소 ───────────────────────────────────────────────────────

    @Test
    void ORDER_주문을_취소하면_CANCEL_상태가_되고_도메인은_재고를_건드리지_않는다() {
        Product product = productWithStock(10);
        Order order = createAllocatedOrder(product, 2); // 예약 2

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL);
        // 예약 해제(release)는 서비스 계층이 InventoryPort(WMS)에 위임한다 — 도메인은 예약 불변
        assertThat(product.getInventory().getReservedQty()).isEqualTo(2);
        assertThat(product.getInventory().getOnHandQty()).isEqualTo(10);
    }

    @Test
    void BACKORDERED_주문을_취소해도_재고는_불변이다() {
        Product product = productWithStock(1);
        Order order = createAllocatedOrder(product, 5); // BACKORDERED, 예약 없음

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL);
        assertThat(product.getInventory().getReservedQty()).isEqualTo(0);
        assertThat(product.getInventory().getOnHandQty()).isEqualTo(1);
    }

    @Test
    void 배송완료된_주문은_취소할_수_없다() {
        Product product = productWithStock(10);
        Order order = createAllocatedOrder(product, 2);
        order.completeDelivery(); // 배송완료(COMP)로 전이 — 재고는 도메인이 건드리지 않음

        assertThatThrownBy(order::cancel)
                .isInstanceOf(IllegalStateException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER);
        assertThat(product.getInventory().getOnHandQty()).isEqualTo(10);
    }

    @Test
    void 이미_취소된_주문은_다시_취소할_수_없다() {
        Product product = productWithStock(10);
        Order order = createAllocatedOrder(product, 2);
        order.cancel(); // CANCEL로 전이

        assertThatThrownBy(order::cancel)
                .isInstanceOf(IllegalStateException.class);

        // 도메인은 예약을 건드리지 않으며(서비스가 위임), 재취소 가드로 상태도 불변
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL);
        assertThat(product.getInventory().getReservedQty()).isEqualTo(2);
    }

    // ── 출고(배송완료) ─────────────────────────────────────────────

    @Test
    void 출고_처리하면_배송상태가_COMP가_되고_도메인은_재고를_건드리지_않는다() {
        Product product = productWithStock(10);
        Order order = createAllocatedOrder(product, 2); // 예약 2

        order.completeDelivery();

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.COMP);
        // 실물 차감(ship)은 서비스 계층이 InventoryPort(WMS)에 위임한다 — 도메인은 재고 불변
        assertThat(product.getInventory().getOnHandQty()).isEqualTo(10);
        assertThat(product.getInventory().getReservedQty()).isEqualTo(2);
    }

    @Test
    void BACKORDERED_주문은_출고할_수_없다() {
        Product product = productWithStock(1);
        Order order = createAllocatedOrder(product, 5); // BACKORDERED

        assertThatThrownBy(order::completeDelivery)
                .isInstanceOf(IllegalStateException.class);

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.READY);
        assertThat(product.getInventory().getOnHandQty()).isEqualTo(1);
    }

    @Test
    void 취소된_주문은_배송완료_처리할_수_없다() {
        Order order = createAllocatedOrder(productWithStock(10), 2);
        order.cancel();

        assertThatThrownBy(order::completeDelivery)
                .isInstanceOf(IllegalStateException.class);

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.READY);
    }

    @Test
    void 이미_배송완료된_주문은_다시_처리할_수_없다() {
        Product product = productWithStock(10);
        Order order = createAllocatedOrder(product, 2);
        order.completeDelivery(); // 1회차: COMP로 전이

        assertThatThrownBy(order::completeDelivery)
                .isInstanceOf(IllegalStateException.class);

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.COMP); // 상태 불변(이중 처리 차단)
    }
}
