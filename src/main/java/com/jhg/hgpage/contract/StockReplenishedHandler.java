package com.jhg.hgpage.contract;

import java.util.Collection;

/**
 * 재고 보충(입고·재고 증가) 통지 포트.
 *
 * <p>재고를 늘리는 WMS 측(발주 입고·재고 조정)이 "이 상품들의 가용분이 늘었다"는 사실만
 * 이 인터페이스로 알린다. 그 통지를 받아 무엇을 하는지(백오더 승격 등)는 OMS의 구현체가 정한다.
 * WMS는 백오더 존재를 알지 못한다.
 *
 * <p>이렇게 부르는 쪽(WMS)이 추상을 소유하고 OMS가 구현하게 해서, WMS→OMS 직접 의존(역방향)을
 * OMS→WMS(정상 방향)로 뒤집는다(의존성 역전). Phase 3에서 "입고 후 OMS에 통지하는 콜백"으로
 * 자연스럽게 진화하는 지점.
 */
public interface StockReplenishedHandler {

    /** 가용분이 늘어난 상품 id들을 통지한다. */
    void onReplenished(Collection<Long> productIds);
}
