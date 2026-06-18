package com.jhg.hgpage.domain;

import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
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
        Order order = createOrderedOrder(productWithStock(10), 2);

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.READY);
    }

    // ── 취소 ───────────────────────────────────────────────────────

    @Test
    void ORDER_주문을_취소하면_CANCEL_상태가_되고_도메인은_재고를_건드리지_않는다() {
        Product product = productWithStock(10);
        Order order = createOrderedOrder(product, 2); // ORDER 상태

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL);
        // 예약/해제는 모두 서비스+InventoryPort(WMS) 책임 — 도메인은 재고를 전혀 건드리지 않는다
        assertThat(product.getInventory().getReservedQty()).isEqualTo(0);
        assertThat(product.getInventory().getOnHandQty()).isEqualTo(10);
    }

    @Test
    void BACKORDERED_주문을_취소해도_재고는_불변이다() {
        Product product = productWithStock(1);
        Order order = createBackorder(product, 5); // BACKORDERED, 예약 없음

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL);
        assertThat(product.getInventory().getReservedQty()).isEqualTo(0);
        assertThat(product.getInventory().getOnHandQty()).isEqualTo(1);
    }

    @Test
    void 배송완료된_주문은_취소할_수_없다() {
        Product product = productWithStock(10);
        Order order = createOrderedOrder(product, 2);
        order.completeDelivery(); // 배송완료(COMP)로 전이 — 재고는 도메인이 건드리지 않음

        assertThatThrownBy(order::cancel)
                .isInstanceOf(IllegalStateException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER);
        assertThat(product.getInventory().getOnHandQty()).isEqualTo(10);
    }

    @Test
    void 이미_취소된_주문은_다시_취소할_수_없다() {
        Product product = productWithStock(10);
        Order order = createOrderedOrder(product, 2);
        order.cancel(); // CANCEL로 전이

        assertThatThrownBy(order::cancel)
                .isInstanceOf(IllegalStateException.class);

        // 도메인은 예약을 건드리지 않으며(서비스가 위임), 재취소 가드로 상태도 불변
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL);
        assertThat(product.getInventory().getReservedQty()).isEqualTo(0);
    }

    // ── 출고(배송완료) ─────────────────────────────────────────────

    @Test
    void 출고_처리하면_배송상태가_COMP가_되고_도메인은_재고를_건드리지_않는다() {
        Product product = productWithStock(10);
        Order order = createOrderedOrder(product, 2); // ORDER 상태

        order.completeDelivery();

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.COMP);
        // 실물 차감(ship)/예약은 모두 서비스+InventoryPort(WMS) 책임 — 도메인은 재고 불변
        assertThat(product.getInventory().getOnHandQty()).isEqualTo(10);
        assertThat(product.getInventory().getReservedQty()).isEqualTo(0);
    }

    @Test
    void BACKORDERED_주문은_출고할_수_없다() {
        Product product = productWithStock(1);
        Order order = createBackorder(product, 5); // BACKORDERED

        assertThatThrownBy(order::completeDelivery)
                .isInstanceOf(IllegalStateException.class);

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.READY);
        assertThat(product.getInventory().getOnHandQty()).isEqualTo(1);
    }

    @Test
    void 취소된_주문은_배송완료_처리할_수_없다() {
        Order order = createOrderedOrder(productWithStock(10), 2);
        order.cancel();

        assertThatThrownBy(order::completeDelivery)
                .isInstanceOf(IllegalStateException.class);

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.READY);
    }

    @Test
    void 이미_배송완료된_주문은_다시_처리할_수_없다() {
        Product product = productWithStock(10);
        Order order = createOrderedOrder(product, 2);
        order.completeDelivery(); // 1회차: COMP로 전이

        assertThatThrownBy(order::completeDelivery)
                .isInstanceOf(IllegalStateException.class);

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.COMP); // 상태 불변(이중 처리 차단)
    }
}
