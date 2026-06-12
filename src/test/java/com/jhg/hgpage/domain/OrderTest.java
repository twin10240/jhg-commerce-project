package com.jhg.hgpage.domain;

import com.jhg.hgpage.domain.enums.DeliveryStatus;
import com.jhg.hgpage.domain.enums.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private Order createOrder(Product product, int quantity) {
        Member member = Member.createUser("테스터", "010-0000-0000", new Address("서울", "관악구", "500"));
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        OrderItem orderItem = OrderItem.createOrderItem(product, product.getPrice(), quantity);
        return Order.createOrder(member, delivery, orderItem);
    }

    @Test
    void 주문을_생성하면_배송상태가_READY로_초기화된다() {
        Order order = createOrder(productWithStock(10), 2);

        assertThat(order.getDelivery().getStatus()).isEqualTo(DeliveryStatus.READY);
    }

    @Test
    void 배송완료된_주문은_취소할_수_없다() {
        Product product = productWithStock(10);
        Order order = createOrder(product, 2); // 재고 10 -> 8
        order.getDelivery().setStatus(DeliveryStatus.COMP);

        assertThatThrownBy(order::cancel)
                .isInstanceOf(IllegalStateException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER); // 취소되지 않음
        assertThat(product.getInventory().getOnHandQty()).isEqualTo(8); // 재고 복구도 없음
    }

    @Test
    void 이미_취소된_주문은_다시_취소할_수_없다() {
        Product product = productWithStock(10);
        Order order = createOrder(product, 2); // 재고 10 -> 8
        order.cancel(); // 재고 8 -> 10

        assertThatThrownBy(order::cancel)
                .isInstanceOf(IllegalStateException.class);

        assertThat(product.getInventory().getOnHandQty()).isEqualTo(10); // 재고 이중 복구 없음
    }

    @Test
    void 배송전_주문은_취소되고_재고가_복구된다() {
        Product product = productWithStock(10);
        Order order = createOrder(product, 2); // 재고 10 -> 8

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL);
        assertThat(product.getInventory().getOnHandQty()).isEqualTo(10);
    }
}
