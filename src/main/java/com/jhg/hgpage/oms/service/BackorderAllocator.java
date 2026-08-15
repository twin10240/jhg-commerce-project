package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.contract.StockReplenishedHandler;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BackorderAllocator implements StockReplenishedHandler {

    private final OrderRepositoryQuery orderRepositoryQuery;

    @Override
    @Transactional
    public void onReplenished(Collection<Long> productIds) {
        allocate(productIds);
    }

    @Transactional
    public int allocate(Collection<Long> productIds) {
        List<Order> backorders = orderRepositoryQuery.findPaidBackordersContaining(productIds);
        LocalDateTime now = LocalDateTime.now();
        for (Order order : backorders) {
            order.setStatus(OrderStatus.ALLOCATION_PENDING);
            order.setAllocationAttemptCount(0);
            order.setNextAllocationAttemptAt(now);
            order.setAllocationFailureCode(null);
            order.setAllocationProcessingAt(null);
            log.info("백오더 재할당 대기: orderId={}", order.getId());
        }
        return backorders.size();
    }
}
