package com.jhg.hgpage.service;

import com.jhg.hgpage.wms.service.InventoryAdjustmentService;
import com.jhg.hgpage.contract.StockReplenishedHandler;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.wms.repository.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리자 재고 조정 + 재고 증가 시 백오더 승격 트리거.
 * productId로 Inventory를 직접 조회한다(Product 객체그래프 미사용).
 */
@ExtendWith(MockitoExtension.class)
class InventoryAdjustmentServiceTest {

    @Mock InventoryRepository inventoryRepository;
    @Mock StockReplenishedHandler stockReplenishedHandler;
    @InjectMocks InventoryAdjustmentService inventoryAdjustmentService;

    private Inventory inventoryOf(int onHand) {
        Inventory inv = Inventory.create(1L);
        inv.setOnHandQty(onHand);
        return inv;
    }

    @Test
    void 재고를_증가시킨다() {
        Inventory inv = inventoryOf(10);
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inv));

        int adjusted = inventoryAdjustmentService.adjust(1L, 5, "정기조사");

        assertThat(adjusted).isEqualTo(15);
        assertThat(inv.getOnHandQty()).isEqualTo(15);
    }

    @Test
    void 재고를_감소시킨다() {
        Inventory inv = inventoryOf(10);
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inv));

        int adjusted = inventoryAdjustmentService.adjust(1L, -3, "파손");

        assertThat(adjusted).isEqualTo(7);
        assertThat(inv.getOnHandQty()).isEqualTo(7);
    }

    @Test
    void 재고가_음수가_되는_조정은_거부하고_재고를_보존한다() {
        Inventory inv = inventoryOf(10);
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> inventoryAdjustmentService.adjust(1L, -11, "조정"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(inv.getOnHandQty()).isEqualTo(10);
    }

    @Test
    void 없는_상품을_조정하면_EntityNotFoundException을_던진다() {
        when(inventoryRepository.findByProductId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryAdjustmentService.adjust(99L, 1, "조정"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void 재고를_증가시키면_백오더_할당을_트리거한다() {
        Inventory inv = inventoryOf(10);
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inv));

        inventoryAdjustmentService.adjust(1L, 5, "정기조사");

        verify(stockReplenishedHandler).onReplenished(List.of(1L));
    }

    @Test
    void 재고_감소는_백오더_할당을_트리거하지_않는다() {
        Inventory inv = inventoryOf(10);
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inv));

        inventoryAdjustmentService.adjust(1L, -3, "파손");

        verify(stockReplenishedHandler, never()).onReplenished(any());
    }

    @Test
    void 예약된_수량_아래로_감소시키는_조정은_거부한다() {
        Inventory inv = inventoryOf(10);
        inv.setReservedQty(4);
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> inventoryAdjustmentService.adjust(1L, -7, "조정"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(inv.getOnHandQty()).isEqualTo(10);
    }
}
