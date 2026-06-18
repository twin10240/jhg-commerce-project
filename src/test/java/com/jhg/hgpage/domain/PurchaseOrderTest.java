package com.jhg.hgpage.domain;

import com.jhg.hgpage.wms.domain.PurchaseOrder;
import com.jhg.hgpage.wms.domain.PurchaseOrderItem;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.wms.domain.enums.PurchaseOrderStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PurchaseOrderTest {

    private Product productWithStock(int stock) {
        Product product = new Product();
        product.setName("상품");
        product.setPrice(10000);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(stock);
        product.setInventory(inventory);
        return product;
    }

    @Test
    void 발주를_생성하면_ORDERED_상태로_초기화된다() {
        Product product = productWithStock(10);

        PurchaseOrder po = PurchaseOrder.create("긴급 발주", PurchaseOrderItem.create(product, 5));

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(po.getMemo()).isEqualTo("긴급 발주");
        assertThat(po.getCreatedAt()).isNotNull();
        assertThat(po.getReceivedAt()).isNull();
        assertThat(po.getItems()).hasSize(1);
        // 발주 생성만으로는 재고가 늘지 않는다
        assertThat(product.getInventory().getOnHandQty()).isEqualTo(10);
    }

    @Test
    void 입고하면_모든_품목의_재고가_늘고_RECEIVED가_된다() {
        Product product1 = productWithStock(10);
        Product product2 = productWithStock(0);
        PurchaseOrder po = PurchaseOrder.create("정기 발주",
                PurchaseOrderItem.create(product1, 5),
                PurchaseOrderItem.create(product2, 20));

        po.receive();

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(po.getReceivedAt()).isNotNull();
        assertThat(product1.getInventory().getOnHandQty()).isEqualTo(15);
        assertThat(product2.getInventory().getOnHandQty()).isEqualTo(20);
    }

    @Test
    void 이미_입고된_발주는_다시_입고할_수_없고_재고도_불변이다() {
        Product product = productWithStock(10);
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(product, 5));
        po.receive(); // 10 -> 15

        assertThatThrownBy(po::receive)
                .isInstanceOf(IllegalStateException.class);

        assertThat(product.getInventory().getOnHandQty()).isEqualTo(15);
    }
}
