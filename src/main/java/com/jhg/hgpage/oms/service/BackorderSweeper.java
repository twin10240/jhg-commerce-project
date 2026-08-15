package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 보상 스윕(S4) — 콜백 유실 시 유료 백오더를 비동기 할당 대기에 다시 넣는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackorderSweeper {

    private final OrderRepositoryQuery orderRepositoryQuery;
    private final BackorderAllocator backorderAllocator;

    // initialDelay = 주기와 동일: 기동 직후(풀 컨텍스트 테스트 포함) 발화 방지
    @Scheduled(fixedDelayString = "${backorder.sweep-delay:60s}",
               initialDelayString = "${backorder.sweep-delay:60s}")
    public void sweep() {
        List<Long> productIds = orderRepositoryQuery.findBackorderedProductIds();
        if (productIds.isEmpty()) {
            return; // 백오더 없음 — WMS 호출 0
        }
        int enqueued = backorderAllocator.allocate(productIds);
        if (enqueued > 0) {
            log.info("보상 스윕: 백오더 {}건 재할당 대기", enqueued);
        }
    }
}
