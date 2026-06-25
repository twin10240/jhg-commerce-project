package com.jhg.hgpage.domain;

import com.jhg.hgpage.wms.domain.PurchaseOrder;
import com.jhg.hgpage.wms.domain.PurchaseOrderItem;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.wms.domain.enums.PurchaseOrderStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PurchaseOrder 도메인은 상태 전이(ORDERED→RECEIVED, 중복 거부)만 책임진다.
 * 실물 재고 증가는 서비스가 WMS 재고에 위임하므로 여기선 재고를 다루지 않는다.
 */
class PurchaseOrderTest {

    private Product productOf(String name) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(10000);
        return product;
    }

    @Test
    void 발주를_생성하면_ORDERED_상태로_초기화된다() {
        PurchaseOrder po = PurchaseOrder.create("긴급 발주", PurchaseOrderItem.create(productOf("상품"), 5));

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(po.getMemo()).isEqualTo("긴급 발주");
        assertThat(po.getCreatedAt()).isNotNull();
        assertThat(po.getReceivedAt()).isNull();
        assertThat(po.getItems()).hasSize(1);
    }

    @Test
    void 입고하면_RECEIVED가_되고_입고시각이_찍힌다() {
        PurchaseOrder po = PurchaseOrder.create("정기 발주",
                PurchaseOrderItem.create(productOf("상품1"), 5),
                PurchaseOrderItem.create(productOf("상품2"), 20));

        po.receive();

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(po.getReceivedAt()).isNotNull();
    }

    @Test
    void 이미_입고된_발주는_다시_입고할_수_없다() {
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(productOf("상품"), 5));
        po.receive();

        assertThatThrownBy(po::receive).isInstanceOf(IllegalStateException.class);
    }
}
