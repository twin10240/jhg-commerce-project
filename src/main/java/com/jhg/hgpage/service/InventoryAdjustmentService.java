package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Inventory;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 관리자 재고 수동 조정 + 재고 증가 시 백오더 승격 트리거.
 *
 * <p>이 책임을 {@link InventoryService}(InventoryPort 구현)에서 분리한 이유:
 * 승격 트리거는 {@link StockReplenishedHandler}에 의존하는데, 그 핸들러(OMS 승격기)는
 * 다시 예약(reserve)을 위해 InventoryPort를 호출한다. 둘이 한 빈에 있으면
 * "InventoryService → 핸들러 → InventoryService" 생성자 순환이 된다.
 * 조정 책임을 이 빈으로 떼어내면 InventoryPort 구현체는 핸들러에 의존하지 않아 순환이 사라진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryAdjustmentService {

    private final ProductRepository productRepository;
    private final StockReplenishedHandler stockReplenishedHandler;

    /**
     * 관리자 재고 수동 조정(+/-). 조정 후 수량을 반환한다.
     * 감사 테이블이 없으므로 사유(reason)는 로그로만 남긴다.
     * 동시 예약과의 충돌은 Inventory의 @Version 낙관적 락이 보호한다.
     */
    @Transactional
    public int adjust(Long productId, int delta, String reason) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product", productId));

        Inventory inventory = product.getInventory();
        int adjusted = inventory.getOnHandQty() + delta;
        if (adjusted < 0) {
            throw new IllegalArgumentException("재고는 0 미만이 될 수 없습니다. (현재 " + inventory.getOnHandQty() + "개)");
        }
        // 예약분(주문이 잡아둔 수량)을 침범하는 감소는 거부 — 가용 수량이 음수가 되는 것을 방지
        if (adjusted < inventory.getReservedQty()) {
            throw new IllegalArgumentException("예약된 수량(" + inventory.getReservedQty() + "개) 미만으로 줄일 수 없습니다.");
        }

        inventory.setOnHandQty(adjusted);
        log.info("재고 조정: productId={}, delta={}, adjusted={}, reason={}", productId, delta, adjusted, reason);

        // 가용분이 늘었으면 통지한다(백오더 승격은 OMS 구현체가 처리)
        if (delta > 0) {
            stockReplenishedHandler.onReplenished(List.of(productId));
        }

        return adjusted;
    }
}
