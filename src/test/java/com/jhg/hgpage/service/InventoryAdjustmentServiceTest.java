package com.jhg.hgpage.service;

import com.jhg.hgpage.contract.StockReplenishedHandler;
import com.jhg.hgpage.wms.adapter.WmsInventoryAdapter;
import com.jhg.hgpage.wms.service.InventoryAdjustmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryAdjustmentServiceTest {

    @Mock WmsInventoryAdapter wmsInventoryAdapter;
    @Mock StockReplenishedHandler stockReplenishedHandler;
    @InjectMocks InventoryAdjustmentService inventoryAdjustmentService;

    @Test
    void 재고를_증가시키면_어댑터를_호출하고_백오더를_트리거한다() {
        when(wmsInventoryAdapter.adjust(1L, 5, "정기조사")).thenReturn(15);

        int result = inventoryAdjustmentService.adjust(1L, 5, "정기조사");

        assertThat(result).isEqualTo(15);
        verify(stockReplenishedHandler).onReplenished(List.of(1L));
    }

    @Test
    void 재고를_감소시키면_어댑터를_호출하고_백오더는_트리거하지_않는다() {
        when(wmsInventoryAdapter.adjust(1L, -3, "파손")).thenReturn(7);

        int result = inventoryAdjustmentService.adjust(1L, -3, "파손");

        assertThat(result).isEqualTo(7);
        verify(stockReplenishedHandler, never()).onReplenished(any());
    }

    @Test
    void 어댑터_예외는_그대로_전파된다() {
        when(wmsInventoryAdapter.adjust(1L, -99, "조정"))
                .thenThrow(new IllegalArgumentException("재고는 0 미만이 될 수 없습니다."));

        assertThatThrownBy(() -> inventoryAdjustmentService.adjust(1L, -99, "조정"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(stockReplenishedHandler, never()).onReplenished(any());
    }
}
