package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.contract.InventoryPort;
import com.jhg.hgpage.oms.domain.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 재고 할당 정책(전부-아니면-백오더).
 *
 * <p>주문의 전 라인을 InventoryPort로 한 번에 예약 시도해, 성공하면 ORDER, 부족하면 BACKORDERED로 표시한다.
 * 신규 주문(OrderService.order)과 입고 후 백오더 승격(BackorderAllocator)이 같은 정책을 공유한다.
 *
 * <p>이 컴포넌트가 InventoryPort에만 의존하고(승격기가 아니라), 재고 증가 통지는
 * {@link InventoryAdjustmentService}가 담당하도록 분리한 덕분에, 승격 경로에서 발생하던
 * "InventoryService ↔ 승격기" 생성자 순환이 끊긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderAllocationService {

    private final InventoryPort inventoryPort;

    /** 전 라인 가용하면 예약하고 ORDER, 하나라도 부족하면 예약 없이 BACKORDERED로 표시한다. */
    @Transactional
    public void allocate(Order order) {
        if (inventoryPort.reserveAll(order.quantitiesByProductId())) {
            order.markOrdered();
        } else {
            order.markBackordered();
        }
    }
}
