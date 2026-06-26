package com.jhg.hgpage.domain;

import com.jhg.hgpage.wms.domain.PurchaseOrder;
import com.jhg.hgpage.wms.domain.PurchaseOrderItem;
import com.jhg.hgpage.wms.domain.enums.PurchaseOrderStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PurchaseOrder 도메인은 상태 전이(ORDERED→RECEIVED, 중복 거부)만 책임진다.
 * 품목은 productId만 보유한다(catalog 객체그래프 없음).
 */
class PurchaseOrderTest {

    @Test
    void 발주를_생성하면_ORDERED_상태로_초기화된다() {
        PurchaseOrder po = PurchaseOrder.create("긴급 발주", PurchaseOrderItem.create(1L, 5));

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(po.getMemo()).isEqualTo("긴급 발주");
        assertThat(po.getCreatedAt()).isNotNull();
        assertThat(po.getReceivedAt()).isNull();
        assertThat(po.getItems()).hasSize(1);
        assertThat(po.getItems().get(0).getProductId()).isEqualTo(1L);
    }

    @Test
    void 입고하면_RECEIVED가_되고_입고시각이_찍힌다() {
        PurchaseOrder po = PurchaseOrder.create("정기 발주",
                PurchaseOrderItem.create(1L, 5),
                PurchaseOrderItem.create(2L, 20));

        po.receive();

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(po.getReceivedAt()).isNotNull();
    }

    @Test
    void 이미_입고된_발주는_다시_입고할_수_없다() {
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(1L, 5));
        po.receive();

        assertThatThrownBy(po::receive).isInstanceOf(IllegalStateException.class);
    }
}
