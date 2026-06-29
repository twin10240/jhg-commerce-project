package com.jhg.hgpage.service;

import com.jhg.hgpage.wms.service.InventoryService;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.wms.dto.InventoryRow;
import com.jhg.hgpage.wms.repository.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * InventoryPort 구현(예약/해제/출고) — productId 기반으로 Inventory를 직접 조회·변경한다.
 * 예약은 전부-아니면-실패(원자적)이며, 가용수량(onHand-reserved) 기준으로 판정한다.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock InventoryRepository inventoryRepository;
    @InjectMocks InventoryService inventoryService;

    private Inventory inventoryOf(long productId, int onHand, int reserved) {
        Inventory inv = Inventory.create(productId);
        inv.setOnHandQty(onHand);
        inv.setReservedQty(reserved);
        return inv;
    }

    @Test
    void 전_라인이_가용하면_모두_예약하고_true를_반환한다() {
        Inventory i1 = inventoryOf(1L, 10, 0);
        Inventory i2 = inventoryOf(2L, 10, 0);
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i1, i2));

        boolean reserved = inventoryService.reserveAll(Map.of(1L, 2, 2L, 1));

        assertThat(reserved).isTrue();
        assertThat(i1.getReservedQty()).isEqualTo(2);
        assertThat(i2.getReservedQty()).isEqualTo(1);
    }

    @Test
    void 한_라인이라도_부족하면_아무것도_예약하지_않고_false를_반환한다() {
        Inventory i1 = inventoryOf(1L, 10, 0);
        Inventory i2 = inventoryOf(2L, 1, 0); // 가용 1인데 5 요청 → 부족
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i1, i2));

        boolean reserved = inventoryService.reserveAll(Map.of(1L, 2, 2L, 5));

        assertThat(reserved).isFalse();
        assertThat(i1.getReservedQty()).isEqualTo(0);
        assertThat(i2.getReservedQty()).isEqualTo(0);
    }

    @Test
    void 예약은_가용수량_기준이며_예약분을_제외하고_판정한다() {
        Inventory i = inventoryOf(1L, 10, 8); // 가용 2
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i));

        assertThat(inventoryService.reserveAll(Map.of(1L, 2))).isTrue();
        assertThat(i.getReservedQty()).isEqualTo(10);
    }

    @Test
    void 출고하면_예약과_실물이_차감된다() {
        Inventory i = inventoryOf(1L, 10, 2);
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i));

        inventoryService.shipAll(Map.of(1L, 2));

        assertThat(i.getOnHandQty()).isEqualTo(8);
        assertThat(i.getReservedQty()).isEqualTo(0);
    }

    @Test
    void 해제하면_예약분이_복구된다() {
        Inventory i = inventoryOf(1L, 10, 3);
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i));

        inventoryService.releaseAll(Map.of(1L, 3));

        assertThat(i.getReservedQty()).isEqualTo(0);
        assertThat(i.getOnHandQty()).isEqualTo(10);
    }

    @Test
    void 없는_상품을_예약하면_EntityNotFoundException을_던진다() {
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of());

        assertThatThrownBy(() -> inventoryService.reserveAll(Map.of(99L, 1)))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void 가용수량을_상품id별로_조회한다() {
        Inventory i1 = inventoryOf(1L, 10, 3); // 가용 7
        Inventory i2 = inventoryOf(2L, 5, 5);  // 가용 0
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i1, i2));

        Map<Long, Integer> available = inventoryService.availableByProductIds(List.of(1L, 2L));

        assertThat(available).containsEntry(1L, 7).containsEntry(2L, 0);
    }

    @Test
    void 재고행을_productId와_보유수량으로_조립한다() {
        Inventory i1 = inventoryOf(1L, 30, 0);
        Inventory i2 = inventoryOf(2L, 0, 0);
        when(inventoryRepository.findAll()).thenReturn(List.of(i1, i2));

        List<InventoryRow> rows = inventoryService.findInventoryRows();

        assertThat(rows).extracting(InventoryRow::productId).containsExactly(1L, 2L);
        assertThat(rows.get(0).onHandQty()).isEqualTo(30);
        assertThat(rows.get(1).onHandQty()).isEqualTo(0);
    }
}
