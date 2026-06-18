package com.jhg.hgpage.domain;

import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.exception.NotEnoughStockException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 예약 재고 모델: availableQty(판매 가용) = onHandQty(실물) - reservedQty(예약).
 * 주문은 reserve, 취소는 release, 출고는 ship(이때 비로소 실물 차감).
 */
class InventoryTest {

    private Inventory inventory(int onHand, int reserved) {
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(onHand);
        inventory.setReservedQty(reserved);
        return inventory;
    }

    @Test
    void 가용수량은_실물에서_예약을_뺀_값이다() {
        assertThat(inventory(10, 3).getAvailableQty()).isEqualTo(7);
    }

    @Test
    void 예약하면_예약수량만_증가하고_실물은_불변이다() {
        Inventory inv = inventory(10, 0);

        inv.reserve(4);

        assertThat(inv.getReservedQty()).isEqualTo(4);
        assertThat(inv.getOnHandQty()).isEqualTo(10);
        assertThat(inv.getAvailableQty()).isEqualTo(6);
    }

    @Test
    void 가용수량을_초과해_예약하면_NotEnoughStockException을_던진다() {
        Inventory inv = inventory(10, 8); // 가용 2

        assertThatThrownBy(() -> inv.reserve(3))
                .isInstanceOf(NotEnoughStockException.class);

        assertThat(inv.getReservedQty()).isEqualTo(8); // 상태 불변
    }

    @Test
    void 예약을_해제하면_예약수량이_감소한다() {
        Inventory inv = inventory(10, 5);

        inv.release(3);

        assertThat(inv.getReservedQty()).isEqualTo(2);
        assertThat(inv.getOnHandQty()).isEqualTo(10);
    }

    @Test
    void 예약된_수량보다_많이_해제하면_IllegalStateException을_던진다() {
        Inventory inv = inventory(10, 2);

        assertThatThrownBy(() -> inv.release(3))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 출고하면_예약과_실물이_함께_감소한다() {
        Inventory inv = inventory(10, 4);

        inv.ship(4);

        assertThat(inv.getReservedQty()).isEqualTo(0);
        assertThat(inv.getOnHandQty()).isEqualTo(6);
    }

    @Test
    void 예약된_수량보다_많이_출고하면_IllegalStateException을_던진다() {
        Inventory inv = inventory(10, 2);

        assertThatThrownBy(() -> inv.ship(3))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 영_이하의_수량은_예약_해제_출고_모두_거부한다() {
        Inventory inv = inventory(10, 5);

        assertThatThrownBy(() -> inv.reserve(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inv.release(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inv.ship(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
