package com.jhg.hgpage.service;

import com.jhg.hgpage.contract.InventoryPort;
import com.jhg.hgpage.oms.service.AllocationProcessor;
import com.jhg.hgpage.oms.service.OrderAllocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AllocationProcessorTest {

    @Mock OrderAllocationService allocationService;
    @Mock InventoryPort inventoryPort;

    AllocationProcessor processor;
    OrderAllocationService.AllocationCommand command =
            new OrderAllocationService.AllocationCommand(Map.of(7L, 2));

    @BeforeEach
    void setUp() {
        processor = new AllocationProcessor(allocationService, inventoryPort);
    }

    @Test
    void 선점후_WMS를_호출하고_예약결과를_완료한다() {
        when(allocationService.claim(10L)).thenReturn(Optional.of(command));
        when(inventoryPort.reserveAll(10L, command.quantities())).thenReturn(true);

        processor.process(10L);

        InOrder calls = inOrder(allocationService, inventoryPort);
        calls.verify(allocationService).claim(10L);
        calls.verify(inventoryPort).reserveAll(10L, command.quantities());
        calls.verify(allocationService).complete(10L, true);
    }

    @Test
    void 명시적_재고부족도_완료결과로_저장한다() {
        when(allocationService.claim(10L)).thenReturn(Optional.of(command));
        when(inventoryPort.reserveAll(10L, command.quantities())).thenReturn(false);

        processor.process(10L);

        verify(allocationService).complete(10L, false);
    }

    @Test
    void 통신실패는_재시도하고_4xx는_즉시_검토로_보낸다() {
        when(allocationService.claim(10L)).thenReturn(Optional.of(command));
        when(allocationService.claim(20L)).thenReturn(Optional.of(command));
        when(inventoryPort.reserveAll(10L, command.quantities()))
                .thenThrow(new ResourceAccessException("timeout"));
        when(inventoryPort.reserveAll(20L, command.quantities()))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        processor.process(10L);
        processor.process(20L);

        verify(allocationService).retryOrReview(10L, "WMS_UNAVAILABLE");
        verify(allocationService).manualReview(20L, "WMS_400");
        verify(allocationService, never()).complete(10L, false);
    }

    @Test
    void 선점하지_못하면_WMS를_호출하지_않는다() {
        when(allocationService.claim(10L)).thenReturn(Optional.empty());

        processor.process(10L);

        verifyNoInteractions(inventoryPort);
    }
}
