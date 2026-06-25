package com.jhg.hgpage.wms.service;

import com.jhg.hgpage.contract.InventoryPort;
import com.jhg.hgpage.contract.InventoryQueryPort;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.catalog.ProductRepository;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.wms.dto.InventoryRow;
import com.jhg.hgpage.wms.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
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
    private final ProductRepository productRepository;

    /** 관리자 재고화면 행 조립: WMS 재고(보유수량) + 카탈로그(이름·가격)를 productId로 합친다. */
    public List<InventoryRow> findInventoryRows() {
        List<Inventory> inventories = inventoryRepository.findAll();
        List<Long> productIds = inventories.stream().map(Inventory::getProductId).toList();
        Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return inventories.stream()
                .map(inv -> {
                    Product p = products.get(inv.getProductId());
                    if (p == null) {
                        throw new EntityNotFoundException("Product", inv.getProductId());
                    }
                    return new InventoryRow(p.getId(), p.getName(), p.getPrice(), inv.getOnHandQty());
                })
                .sorted(Comparator.comparing(InventoryRow::id))
                .toList();
    }

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
