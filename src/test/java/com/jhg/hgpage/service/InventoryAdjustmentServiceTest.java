package com.jhg.hgpage.service;

import com.jhg.hgpage.wms.service.InventoryAdjustmentService;

import com.jhg.hgpage.contract.StockReplenishedHandler;

import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.catalog.ProductRepository;
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
 * (InventoryPort 구현인 InventoryService와 분리된 빈 — 순환 의존 회피 목적.)
 */
@ExtendWith(MockitoExtension.class)
class InventoryAdjustmentServiceTest {

    @Mock ProductRepository productRepository;
    @Mock StockReplenishedHandler stockReplenishedHandler;
    @InjectMocks InventoryAdjustmentService inventoryAdjustmentService;

    private Product productWithStock(int stock) {
        Product product = new Product();
        product.setId(1L);
        product.setName("상품1");
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(stock);
        product.setInventory(inventory);
        return product;
    }

    @Test
    void 재고를_증가시킨다() {
        Product product = productWithStock(10);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        int adjusted = inventoryAdjustmentService.adjust(1L, 5, "정기조사");

        assertThat(adjusted).isEqualTo(15);
        assertThat(product.getInventory().getOnHandQty()).isEqualTo(15);
    }

    @Test
    void 재고를_감소시킨다() {
        Product product = productWithStock(10);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        int adjusted = inventoryAdjustmentService.adjust(1L, -3, "파손");

        assertThat(adjusted).isEqualTo(7);
        assertThat(product.getInventory().getOnHandQty()).isEqualTo(7);
    }

    @Test
    void 재고가_음수가_되는_조정은_거부하고_재고를_보존한다() {
        Product product = productWithStock(10);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> inventoryAdjustmentService.adjust(1L, -11, "조정"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(product.getInventory().getOnHandQty()).isEqualTo(10);
    }

    @Test
    void 없는_상품을_조정하면_EntityNotFoundException을_던진다() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryAdjustmentService.adjust(99L, 1, "조정"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void 재고를_증가시키면_백오더_할당을_트리거한다() {
        Product product = productWithStock(10);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        inventoryAdjustmentService.adjust(1L, 5, "정기조사");

        verify(stockReplenishedHandler).onReplenished(List.of(1L));
    }

    @Test
    void 재고_감소는_백오더_할당을_트리거하지_않는다() {
        Product product = productWithStock(10);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        inventoryAdjustmentService.adjust(1L, -3, "파손");

        verify(stockReplenishedHandler, never()).onReplenished(any());
    }

    @Test
    void 예약된_수량_아래로_감소시키는_조정은_거부한다() {
        Product product = productWithStock(10);
        product.getInventory().setReservedQty(4); // 예약 4 → 실물을 4 미만으로 줄일 수 없음
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> inventoryAdjustmentService.adjust(1L, -7, "조정"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(product.getInventory().getOnHandQty()).isEqualTo(10);
    }
}
