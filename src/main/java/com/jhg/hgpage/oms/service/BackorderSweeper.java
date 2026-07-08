package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 보상 스윕(S4) — 콜백 유실(OMS 다운·통지 타임아웃)과 "예약 못 해본 백오더"(WMS 다운 중 접수)를
 * 주기적으로 회수한다. 승격 정책은 BackorderAllocator를 그대로 재사용 — 트리거만 둘(콜백/스케줄).
 * 스윕과 콜백이 같은 주문을 동시 승격 시도해도 WMS 예약 원장 orderId 멱등으로 같은 결과에 수렴한다.
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
        int promoted = backorderAllocator.allocate(productIds);
        if (promoted > 0) {
            log.info("보상 스윕: 백오더 {}건 승격", promoted);
        }
    }
}
