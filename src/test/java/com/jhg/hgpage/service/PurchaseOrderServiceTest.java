package com.jhg.hgpage.service;

import com.jhg.hgpage.wms.service.PurchaseOrderService;
import com.jhg.hgpage.contract.StockReplenishedHandler;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.wms.domain.PurchaseOrder;
import com.jhg.hgpage.wms.domain.PurchaseOrderItem;
import com.jhg.hgpage.wms.domain.enums.PurchaseOrderStatus;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.wms.repository.InventoryRepository;
import com.jhg.hgpage.wms.repository.PurchaseOrderRepository;
import com.jhg.hgpage.wms.service.PurchaseOrderService.PurchaseOrderLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceTest {

    @Mock PurchaseOrderRepository purchaseOrderRepository;
    @Mock InventoryRepository inventoryRepository;
    @Mock StockReplenishedHandler stockReplenishedHandler;
    @InjectMocks PurchaseOrderService purchaseOrderService;

    @Test
    void 발주를_생성하면_ORDERED_상태로_저장된다() {
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(Inventory.create(1L)));
        when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        purchaseOrderService.create(List.of(new PurchaseOrderLine(1L, 20)), "긴급 발주");

        ArgumentCaptor<PurchaseOrder> captor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(purchaseOrderRepository).save(captor.capture());
        PurchaseOrder saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(saved.getMemo()).isEqualTo("긴급 발주");
        assertThat(saved.getItems()).hasSize(1);
        assertThat(saved.getItems().get(0).getProductId()).isEqualTo(1L);
        assertThat(saved.getItems().get(0).getQuantity()).isEqualTo(20);
    }

    @Test
    void 품목이_없으면_발주를_거부한다() {
        assertThatThrownBy(() -> purchaseOrderService.create(List.of(), "메모"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(purchaseOrderRepository, never()).save(any());
    }

    @Test
    void 수량이_1_미만이면_발주를_거부한다() {
        assertThatThrownBy(() -> purchaseOrderService.create(List.of(new PurchaseOrderLine(1L, 0)), "메모"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(purchaseOrderRepository, never()).save(any());
    }

    @Test
    void 재고에_없는_상품을_발주하면_EntityNotFoundException을_던진다() {
        when(inventoryRepository.findByProductId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> purchaseOrderService.create(List.of(new PurchaseOrderLine(99L, 5)), "메모"))
                .isInstanceOf(EntityNotFoundException.class);
        verify(purchaseOrderRepository, never()).save(any());
    }

    @Test
    void 입고하면_RECEIVED가_되고_실물_재고가_늘어난다() {
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(1L, 5));
        Inventory inv = Inventory.create(1L);
        inv.setOnHandQty(10);
        when(purchaseOrderRepository.findById(7L)).thenReturn(Optional.of(po));
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(inv));

        purchaseOrderService.receive(7L);

        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(inv.getOnHandQty()).isEqualTo(15);
    }

    @Test
    void 입고_상품의_재고가_없으면_EntityNotFoundException을_던진다() {
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(1L, 5));
        when(purchaseOrderRepository.findById(7L)).thenReturn(Optional.of(po));
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of());

        assertThatThrownBy(() -> purchaseOrderService.receive(7L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("1");
    }

    @Test
    void 입고하면_입고된_상품들의_백오더_할당을_트리거한다() {
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(1L, 5));
        Inventory inv = Inventory.create(1L);
        inv.setOnHandQty(10);
        when(purchaseOrderRepository.findById(7L)).thenReturn(Optional.of(po));
        when(inventoryRepository.findByProductIdIn(any())).thenReturn(List.of(inv));

        purchaseOrderService.receive(7L);

        verify(stockReplenishedHandler).onReplenished(List.of(1L));
    }

    @Test
    void 중복_입고가_거부되면_백오더_할당도_트리거하지_않는다() {
        PurchaseOrder po = PurchaseOrder.create("발주", PurchaseOrderItem.create(1L, 5));
        po.receive();
        when(purchaseOrderRepository.findById(7L)).thenReturn(Optional.of(po));

        assertThatThrownBy(() -> purchaseOrderService.receive(7L))
                .isInstanceOf(IllegalStateException.class);

        verify(stockReplenishedHandler, never()).onReplenished(any());
    }

    @Test
    void 없는_발주를_입고하면_EntityNotFoundException을_던진다() {
        when(purchaseOrderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> purchaseOrderService.receive(99L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("99");
    }
}
