package com.jhg.hgpage.wms.service;

import com.jhg.hgpage.contract.StockReplenishedHandler;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.wms.domain.PurchaseOrder;
import com.jhg.hgpage.wms.domain.PurchaseOrderItem;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.catalog.ProductRepository;
import com.jhg.hgpage.wms.repository.InventoryRepository;
import com.jhg.hgpage.wms.repository.PurchaseOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseOrderService {

    private final ProductRepository productRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final InventoryRepository inventoryRepository;
    private final StockReplenishedHandler stockReplenishedHandler;

    public record PurchaseOrderLine(Long productId, int quantity) {}

    @Transactional
    public Long create(List<PurchaseOrderLine> lines, String memo) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("발주 품목이 없습니다.");
        }

        PurchaseOrderItem[] items = lines.stream()
                .map(line -> {
                    if (line.quantity() < 1) {
                        throw new IllegalArgumentException("발주 수량은 1개 이상이어야 합니다.");
                    }
                    Product product = productRepository.findById(line.productId())
                            .orElseThrow(() -> new EntityNotFoundException("Product", line.productId()));
                    return PurchaseOrderItem.create(product, line.quantity());
                })
                .toArray(PurchaseOrderItem[]::new);

        PurchaseOrder purchaseOrder = purchaseOrderRepository.save(PurchaseOrder.create(memo, items));

        return purchaseOrder.getId();
    }

    @Transactional
    public Long receive(Long poId) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new EntityNotFoundException("PurchaseOrder", poId));

        purchaseOrder.receive(); // 상태 전이(중복 입고 거부)

        // 입고 수량만큼 실물 재고를 늘린다(상품별 합산)
        Map<Long, Integer> qtyByProductId = purchaseOrder.getItems().stream()
                .collect(Collectors.toMap(item -> item.getProduct().getId(),
                        PurchaseOrderItem::getQuantity, Integer::sum));
        Map<Long, Inventory> inventories = inventoryRepository.findByProductIdIn(qtyByProductId.keySet()).stream()
                .collect(Collectors.toMap(Inventory::getProductId, Function.identity()));
        for (Long productId : qtyByProductId.keySet()) {
            if (!inventories.containsKey(productId)) {
                throw new EntityNotFoundException("Inventory", productId);
            }
        }
        qtyByProductId.forEach((productId, qty) -> inventories.get(productId).addOnHandQty(qty));

        // 입고로 가용분이 생겼음을 통지한다(백오더 승격은 OMS 구현체가 처리)
        stockReplenishedHandler.onReplenished(List.copyOf(qtyByProductId.keySet()));

        return purchaseOrder.getId();
    }

    public List<PurchaseOrder> findAllWithItems() {
        return purchaseOrderRepository.findAllWithItems();
    }
}
