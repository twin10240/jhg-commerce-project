package com.jhg.hgpage.wms.service;

import com.jhg.hgpage.contract.InventoryPort;
import com.jhg.hgpage.contract.InventoryQueryPort;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.wms.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * InventoryPort 구현(WMS 재고 변경: 예약/해제/출고) + InventoryQueryPort(가용수량 조회).
 * productId로 Inventory를 직접 조회·변경한다(Product 객체그래프 미사용).
 * <p>OMS의 백오더 승격 트리거는 의도적으로 갖지 않는다({@link InventoryAdjustmentService}로 분리 — 생성자 순환 회피).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService implements InventoryPort, InventoryQueryPort {

    private final InventoryRepository inventoryRepository;

    @Override
    public Map<Long, Integer> availableByProductIds(Collection<Long> productIds) {
        return inventoryRepository.findByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(Inventory::getProductId, Inventory::getAvailableQty));
    }

    @Override
    @Transactional
    public boolean reserveAll(Map<Long, Integer> qtyByProductId) {
        Map<Long, Inventory> inventories = loadInventories(qtyByProductId.keySet());

        boolean allAvailable = qtyByProductId.entrySet().stream()
                .allMatch(e -> inventories.get(e.getKey()).getAvailableQty() >= e.getValue());
        if (!allAvailable) {
            return false;
        }
        qtyByProductId.forEach((productId, qty) -> inventories.get(productId).reserve(qty));
        return true;
    }

    @Override
    @Transactional
    public void shipAll(Map<Long, Integer> qtyByProductId) {
        applyToInventories(qtyByProductId, Inventory::ship);
    }

    @Override
    @Transactional
    public void releaseAll(Map<Long, Integer> qtyByProductId) {
        applyToInventories(qtyByProductId, Inventory::release);
    }

    private void applyToInventories(Map<Long, Integer> qtyByProductId, BiConsumer<Inventory, Integer> operation) {
        Map<Long, Inventory> inventories = loadInventories(qtyByProductId.keySet());
        qtyByProductId.forEach((productId, qty) -> operation.accept(inventories.get(productId), qty));
    }

    /** productId 묶음으로 Inventory를 일괄 로드한다(N+1 회피). 누락 시 EntityNotFoundException. */
    private Map<Long, Inventory> loadInventories(Collection<Long> productIds) {
        Map<Long, Inventory> inventories = inventoryRepository.findByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(Inventory::getProductId, Function.identity()));
        for (Long productId : productIds) {
            if (!inventories.containsKey(productId)) {
                throw new EntityNotFoundException("Inventory", productId);
            }
        }
        return inventories;
    }
}
