package com.jhg.hgpage.service;

import java.util.Collection;
import java.util.Map;

/**
 * OMS가 WMS 재고의 판매 가용 수량을 조회하는 읽기 포트.
 *
 * <p>변경(예약/해제/출고)을 다루는 {@link InventoryPort}와 의도적으로 분리한 조회 전용 포트다(CQRS).
 * OMS 판매 화면(상품 그리드)이 {@code Product → Inventory} 객체 그래프로 WMS 데이터를 직접 들추던
 * 결합을 끊고, "이 상품들의 가용수량을 알려 달라"는 정상 방향(OMS→WMS) 질의로 바꾼다.
 * Phase 3에서 이 포트는 WMS의 "재고 조회 GET" REST 호출로 진화한다.
 *
 * <p>그리드는 여러 상품을 한 번에 그리므로 상품 id 묶음을 받는 배치형이다(라인별 재조회 N+1 회피).
 */
public interface InventoryQueryPort {

    /**
     * 상품 id별 판매 가용 수량(availableQty = onHand − reserved)을 조회한다.
     * 존재하지 않는 id는 결과 맵에 포함되지 않는다(호출 측에서 0으로 기본 처리).
     */
    Map<Long, Integer> availableByProductIds(Collection<Long> productIds);
}
