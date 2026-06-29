package com.jhg.hgpage.service;

import com.jhg.hgpage.wms.service.InventoryService;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.wms.domain.Reservation;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.wms.dto.InventoryRow;
import com.jhg.hgpage.wms.repository.InventoryRepository;
import com.jhg.hgpage.wms.repository.ReservationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * InventoryPort 구현(예약/해제/출고) — orderId 자연키로 멱등이며, productId 기반으로 Inventory를 직접 변경한다.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock InventoryRepository inventoryRepository;
    @Mock ReservationRepository reservationRepository;
    @InjectMocks InventoryService inventoryService;

    private Inventory inventoryOf(long productId, int onHand, int reserved) {
        Inventory inv = Inventory.create(productId);
        inv.setOnHandQty(onHand);
        inv.setReservedQty(reserved);
        return inv;
    }

    @Test
    void 전_라인이_가용하면_모두_예약하고_원장을_기록하며_true를_반환한다() {
        Inventory i1 = inventoryOf(1L, 10, 0);
        Inventory i2 = inventoryOf(2L, 10, 0);
        when(reservationRepository.findByOrderId(100L)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i1, i2));

        boolean reserved = inventoryService.reserveAll(100L, Map.of(1L, 2, 2L, 1));

        assertThat(reserved).isTrue();
        assertThat(i1.getReservedQty()).isEqualTo(2);
        assertThat(i2.getReservedQty()).isEqualTo(1);
        verify(reservationRepository).save(any(Reservation.class));
    }

    @Test
    void 한_라인이라도_부족하면_아무것도_예약하지_않고_원장도_남기지_않으며_false() {
        Inventory i1 = inventoryOf(1L, 10, 0);
        Inventory i2 = inventoryOf(2L, 1, 0);
        when(reservationRepository.findByOrderId(100L)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i1, i2));

        boolean reserved = inventoryService.reserveAll(100L, Map.of(1L, 2, 2L, 5));

        assertThat(reserved).isFalse();
        assertThat(i1.getReservedQty()).isEqualTo(0);
        assertThat(i2.getReservedQty()).isEqualTo(0);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void 같은_orderId로_다시_예약하면_재예약하지_않고_true를_반환한다() {
        when(reservationRepository.findByOrderId(100L)).thenReturn(Optional.of(Reservation.reserve(100L)));

        boolean reserved = inventoryService.reserveAll(100L, Map.of(1L, 2));

        assertThat(reserved).isTrue();
        verify(inventoryRepository, never()).findByProductIdIn(any());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void 예약은_가용수량_기준이며_예약분을_제외하고_판정한다() {
        Inventory i = inventoryOf(1L, 10, 8); // 가용 2
        when(reservationRepository.findByOrderId(100L)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i));

        assertThat(inventoryService.reserveAll(100L, Map.of(1L, 2))).isTrue();
        assertThat(i.getReservedQty()).isEqualTo(10);
    }

    @Test
    void 출고하면_예약과_실물이_차감되고_원장이_SHIPPED가_된다() {
        Inventory i = inventoryOf(1L, 10, 2);
        Reservation r = Reservation.reserve(100L);
        when(reservationRepository.findByOrderId(100L)).thenReturn(Optional.of(r));
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i));

        inventoryService.shipAll(100L, Map.of(1L, 2));

        assertThat(i.getOnHandQty()).isEqualTo(8);
        assertThat(i.getReservedQty()).isEqualTo(0);
    }

    @Test
    void 이미_출고된_주문의_출고요청은_무시된다() {
        Inventory i = inventoryOf(1L, 10, 2);
        Reservation r = Reservation.reserve(100L);
        r.ship();
        when(reservationRepository.findByOrderId(100L)).thenReturn(Optional.of(r));

        inventoryService.shipAll(100L, Map.of(1L, 2));

        // 실물·예약이 한 번 더 차감되지 않는다
        assertThat(i.getOnHandQty()).isEqualTo(10);
        assertThat(i.getReservedQty()).isEqualTo(2);
        verify(inventoryRepository, never()).findByProductIdIn(any());
    }

    @Test
    void 해제하면_예약분이_복구되고_원장이_RELEASED가_된다() {
        Inventory i = inventoryOf(1L, 10, 3);
        Reservation r = Reservation.reserve(100L);
        when(reservationRepository.findByOrderId(100L)).thenReturn(Optional.of(r));
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(i));

        inventoryService.releaseAll(100L, Map.of(1L, 3));

        assertThat(i.getReservedQty()).isEqualTo(0);
        assertThat(i.getOnHandQty()).isEqualTo(10);
    }

    @Test
    void 없는_상품을_예약하면_EntityNotFoundException을_던진다() {
        when(reservationRepository.findByOrderId(100L)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of());

        assertThatThrownBy(() -> inventoryService.reserveAll(100L, Map.of(99L, 1)))
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
