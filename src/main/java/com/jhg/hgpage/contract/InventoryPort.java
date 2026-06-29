package com.jhg.hgpage.contract;

import java.util.Map;

/**
 * OMS가 WMS 재고에 대해 수행하는 연산 포트(예약/해제/출고).
 *
 * <p>주문(OMS)이 재고(WMS)에게 "예약/해제/출고해 달라"고 부탁하는 정상 방향(OMS→WMS) 의존을
 * 인터페이스로 추상화한다. 예약/해제/출고 호출이 `Order` 엔티티 안에 박혀 있던 것을
 * 서비스 계층으로 끌어올려, 엔티티가 WMS 도메인(`Product`/`Inventory`)을 객체 그래프로 직접
 * 타고 들어가던 결합을 끊는다. 구현체(WMS)는 이 포트를 통해서만 재고를 변경한다.
 *
 * <p>주문은 여러 라인이라 모든 연산은 상품 id→수량 맵을 받는 배치형이다(N+1 재조회 회피,
 * Phase 3의 "주문당 통신 1회"와 일치).
 */
public interface InventoryPort {

    /**
     * 예약(전부-아니면-실패): 전 상품이 가용하면 모두 예약하고 true,
     * 하나라도 부족하면 아무것도 예약하지 않고 false를 반환한다(원자적).
     * 가용성 검사와 예약을 WMS가 한 연산으로 처리해 check-then-act 경합을 없앤다.
     */
    boolean reserveAll(Long orderId, Map<Long, Integer> qtyByProductId);

    /**
     * 출고: 전 상품의 실물 재고를 차감한다(예약분도 함께 해소).
     * 출고 시점에 비로소 실물이 빠진다.
     */
    void shipAll(Long orderId, Map<Long, Integer> qtyByProductId);

    /**
     * 예약 해제: 전 상품의 예약분을 되돌린다(가용분 복구).
     * ORDER 주문 취소 시점에 호출한다.
     */
    void releaseAll(Long orderId, Map<Long, Integer> qtyByProductId);
}
