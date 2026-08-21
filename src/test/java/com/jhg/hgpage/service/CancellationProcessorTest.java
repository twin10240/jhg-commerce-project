package com.jhg.hgpage.service;

import com.jhg.hgpage.contract.InventoryPort;
import com.jhg.hgpage.oms.service.BackorderAllocator;
import com.jhg.hgpage.oms.service.CancellationProcessor;
import com.jhg.hgpage.oms.service.OrderCancellationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancellationProcessorTest {

    @Mock OrderCancellationService cancellationService;
    @Mock InventoryPort inventoryPort;
    @Mock BackorderAllocator backorderAllocator;

    CancellationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new CancellationProcessor(cancellationService, inventoryPort, backorderAllocator);
    }

    @Test
    void 해제필요_취소는_claim_외부해제_완료후_백오더재할당_순서다() {
        OrderCancellationService.CancellationClaim claim =
                new OrderCancellationService.CancellationClaim(3, true, Map.of(7L, 2));
        when(cancellationService.claim(10L)).thenReturn(Optional.of(claim));
        when(cancellationService.complete(10L, 3)).thenReturn(true);

        processor.process(10L);

        InOrder calls = inOrder(cancellationService, inventoryPort, backorderAllocator);
        calls.verify(cancellationService).claim(10L);
        calls.verify(inventoryPort).releaseAll(10L, claim.quantities());
        calls.verify(cancellationService).complete(10L, 3);
        calls.verify(backorderAllocator).allocate(claim.quantities().keySet());
    }

    @Test
    void 해제불필요_취소는_WMS없이_완료한다() {
        OrderCancellationService.CancellationClaim claim =
                new OrderCancellationService.CancellationClaim(1, false, Map.of(7L, 2));
        when(cancellationService.claim(10L)).thenReturn(Optional.of(claim));
        when(cancellationService.complete(10L, 1)).thenReturn(true);

        processor.process(10L);

        verify(inventoryPort, never()).releaseAll(10L, claim.quantities());
        verify(cancellationService).complete(10L, 1);
        verify(backorderAllocator, never()).allocate(claim.quantities().keySet());
    }

    @Test
    void WMS_일시예외는_제한재시도로_보낸다() {
        OrderCancellationService.CancellationClaim claim =
                new OrderCancellationService.CancellationClaim(2, true, Map.of(7L, 2));
        when(cancellationService.claim(10L)).thenReturn(Optional.of(claim));
        doThrow(new IllegalStateException("down")).when(inventoryPort).releaseAll(10L, claim.quantities());

        processor.process(10L);

        verify(cancellationService).retryOrReview(10L, 2, "WMS_UNAVAILABLE");
        verify(cancellationService, never()).complete(10L, 2);
    }

    @Test
    void WMS_4xx는_즉시_수동검토로_보낸다() {
        OrderCancellationService.CancellationClaim claim =
                new OrderCancellationService.CancellationClaim(2, true, Map.of(7L, 2));
        when(cancellationService.claim(10L)).thenReturn(Optional.of(claim));
        doThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST))
                .when(inventoryPort).releaseAll(10L, claim.quantities());

        processor.process(10L);

        verify(cancellationService).manualReview(10L, 2, "WMS_400");
        verify(cancellationService, never()).complete(10L, 2);
    }
}
