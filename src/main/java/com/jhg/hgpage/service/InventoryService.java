package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Inventory;
import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * InventoryPort 구현(WMS 재고 변경: 예약/해제/출고).
 * <p>OMS의 백오더 승격을 트리거하는 책임(재고 증가 통지)은 의도적으로 갖지 않는다.
 * 그 책임은 {@link InventoryAdjustmentService}에 분리해, 이 빈이 {@link StockReplenishedHandler}에
 * 의존하지 않게 한다 — 그래야 "예약(reserve)을 제공하는 빈 ↔ 승격기" 사이의 생성자 순환이 생기지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService implements InventoryPort {

    private final ProductRepository productRepository;

    /**
     * 예약(InventoryPort 구현, 전부-아니면-실패): 전 상품이 가용하면 모두 예약하고 true,
     * 하나라도 부족하면 아무것도 예약하지 않고 false를 반환한다.
     */
    @Override
    @Transactional
    public boolean reserveAll(Map<Long, Integer> qtyByProductId) {
        Map<Long, Product> products = loadProducts(qtyByProductId.keySet());

        // 먼저 전 라인 가용성을 검사하고(부분 예약 방지), 모두 가용할 때만 예약한다.
        boolean allAvailable = qtyByProductId.entrySet().stream()
                .allMatch(e -> products.get(e.getKey()).getInventory().getAvailableQty() >= e.getValue());
        if (!allAvailable) {
            return false;
        }
        qtyByProductId.forEach((productId, qty) -> products.get(productId).getInventory().reserve(qty));
        return true;
    }

    /** 출고(InventoryPort 구현): 상품들의 실물 재고를 일괄 차감한다. */
    @Override
    @Transactional
    public void shipAll(Map<Long, Integer> qtyByProductId) {
        applyToInventories(qtyByProductId, Inventory::ship);
    }

    /** 예약 해제(InventoryPort 구현): 상품들의 예약분을 일괄 복구한다. */
    @Override
    @Transactional
    public void releaseAll(Map<Long, Integer> qtyByProductId) {
        applyToInventories(qtyByProductId, Inventory::release);
    }

    /** 상품 id→수량 맵의 각 재고에 연산을 적용한다(release/ship 공용). */
    private void applyToInventories(Map<Long, Integer> qtyByProductId, BiConsumer<Inventory, Integer> operation) {
        Map<Long, Product> products = loadProducts(qtyByProductId.keySet());
        qtyByProductId.forEach((productId, qty) -> operation.accept(products.get(productId).getInventory(), qty));
    }

    /**
     * 상품들을 id→Product 맵으로 일괄 로드한다(라인별 재조회 N+1 회피).
     * 누락된 id가 있으면 EntityNotFoundException.
     */
    private Map<Long, Product> loadProducts(Collection<Long> productIds) {
        Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        for (Long productId : productIds) {
            if (!products.containsKey(productId)) {
                throw new EntityNotFoundException("Product", productId);
            }
        }
        return products;
    }
}
