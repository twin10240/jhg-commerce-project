package com.jhg.hgpage.service;

import com.jhg.hgpage.wms.service.InventoryService;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * InventoryPort 구현(예약/해제/출고) — 실제 Inventory 객체에 대한 재고 변경을 검증한다.
 * 예약은 전부-아니면-실패(원자적)이며, 가용수량(onHand-reserved) 기준으로 판정한다.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock ProductRepository productRepository;
    @InjectMocks InventoryService inventoryService;

    private Product productWithStock(long id, int onHand, int reserved) {
        Product product = new Product();
        product.setId(id);
        product.setName("상품" + id);
        Inventory inventory = new Inventory();
        inventory.setOnHandQty(onHand);
        inventory.setReservedQty(reserved);
        product.setInventory(inventory);
        return product;
    }

    @Test
    void 전_라인이_가용하면_모두_예약하고_true를_반환한다() {
        Product p1 = productWithStock(1L, 10, 0);
        Product p2 = productWithStock(2L, 10, 0);
        when(productRepository.findAllById(any())).thenReturn(List.of(p1, p2));

        boolean reserved = inventoryService.reserveAll(Map.of(1L, 2, 2L, 1));

        assertThat(reserved).isTrue();
        assertThat(p1.getInventory().getReservedQty()).isEqualTo(2);
        assertThat(p2.getInventory().getReservedQty()).isEqualTo(1);
    }

    @Test
    void 한_라인이라도_부족하면_아무것도_예약하지_않고_false를_반환한다() {
        Product p1 = productWithStock(1L, 10, 0);
        Product p2 = productWithStock(2L, 1, 0); // 가용 1인데 5 요청 → 부족
        when(productRepository.findAllById(any())).thenReturn(List.of(p1, p2));

        boolean reserved = inventoryService.reserveAll(Map.of(1L, 2, 2L, 5));

        assertThat(reserved).isFalse();
        assertThat(p1.getInventory().getReservedQty()).isEqualTo(0); // 가용 라인도 미예약
        assertThat(p2.getInventory().getReservedQty()).isEqualTo(0);
    }

    @Test
    void 예약은_가용수량_기준이며_예약분을_제외하고_판정한다() {
        Product p = productWithStock(1L, 10, 8); // 가용 2
        when(productRepository.findAllById(any())).thenReturn(List.of(p));

        assertThat(inventoryService.reserveAll(Map.of(1L, 2))).isTrue();
        assertThat(p.getInventory().getReservedQty()).isEqualTo(10);
    }

    @Test
    void 출고하면_예약과_실물이_차감된다() {
        Product p = productWithStock(1L, 10, 2);
        when(productRepository.findAllById(any())).thenReturn(List.of(p));

        inventoryService.shipAll(Map.of(1L, 2));

        assertThat(p.getInventory().getOnHandQty()).isEqualTo(8);
        assertThat(p.getInventory().getReservedQty()).isEqualTo(0);
    }

    @Test
    void 해제하면_예약분이_복구된다() {
        Product p = productWithStock(1L, 10, 3);
        when(productRepository.findAllById(any())).thenReturn(List.of(p));

        inventoryService.releaseAll(Map.of(1L, 3));

        assertThat(p.getInventory().getReservedQty()).isEqualTo(0);
        assertThat(p.getInventory().getOnHandQty()).isEqualTo(10);
    }

    @Test
    void 없는_상품을_예약하면_EntityNotFoundException을_던진다() {
        when(productRepository.findAllById(any())).thenReturn(List.of()); // 없음

        assertThatThrownBy(() -> inventoryService.reserveAll(Map.of(99L, 1)))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void 가용수량을_상품id별로_조회한다() {
        Product p1 = productWithStock(1L, 10, 3); // 가용 7
        Product p2 = productWithStock(2L, 5, 5);  // 가용 0
        when(productRepository.findAllById(any())).thenReturn(List.of(p1, p2));

        Map<Long, Integer> available = inventoryService.availableByProductIds(List.of(1L, 2L));

        assertThat(available).containsEntry(1L, 7).containsEntry(2L, 0);
    }
}
