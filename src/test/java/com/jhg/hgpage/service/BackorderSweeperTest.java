package com.jhg.hgpage.service;

import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import com.jhg.hgpage.oms.service.BackorderAllocator;
import com.jhg.hgpage.oms.service.BackorderSweeper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BackorderSweeperTest {

    OrderRepositoryQuery orderRepositoryQuery = mock(OrderRepositoryQuery.class);
    BackorderAllocator backorderAllocator = mock(BackorderAllocator.class);
    BackorderSweeper sweeper = new BackorderSweeper(orderRepositoryQuery, backorderAllocator);

    @Test
    void 백오더_상품이_있으면_재할당을_트리거한다() {
        when(orderRepositoryQuery.findBackorderedProductIds()).thenReturn(List.of(1L, 2L));

        sweeper.sweep();

        verify(backorderAllocator).allocate(List.of(1L, 2L));
    }

    @Test
    void 백오더가_없으면_재할당을_호출하지_않는다() {
        // WMS 호출 0 보장 — 스윕이 유휴 상태에서 트래픽을 만들지 않는다.
        when(orderRepositoryQuery.findBackorderedProductIds()).thenReturn(List.of());

        sweeper.sweep();

        verify(backorderAllocator, never()).allocate(any());
    }
}
