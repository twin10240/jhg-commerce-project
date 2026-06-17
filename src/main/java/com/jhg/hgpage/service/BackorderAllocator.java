package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Order;
import com.jhg.hgpage.domain.enums.OrderStatus;
import com.jhg.hgpage.repository.OrderRepositoryQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 입고/재고 증가 시 백오더 자동 할당.
 * 해당 상품을 기다리는 BACKORDERED 주문을 오래된 순(FIFO)으로 재할당해
 * 전 라인이 가용해진 주문을 ORDER로 승격(예약)한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackorderAllocator implements StockReplenishedHandler {

    private final OrderRepositoryQuery orderRepositoryQuery;

    /** 재고 보충 통지를 받아 백오더 재할당으로 처리한다(포트 구현). */
    @Override
    @Transactional
    public void onReplenished(Collection<Long> productIds) {
        allocate(productIds);
    }

    /** @return 승격된 주문 수 */
    @Transactional
    public int allocate(Collection<Long> productIds) {
        List<Order> backorders = orderRepositoryQuery.findBackordersContaining(productIds);

        int promoted = 0;
        for (Order order : backorders) {
            order.allocate(); // 전부-아니면-백오더: 부족하면 BACKORDERED 유지
            if (order.getStatus() == OrderStatus.ORDER) {
                promoted++;
                log.info("백오더 승격: orderId={}", order.getId());
            }
        }
        return promoted;
    }
}
