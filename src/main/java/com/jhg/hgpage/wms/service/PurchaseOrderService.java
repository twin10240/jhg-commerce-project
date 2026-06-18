package com.jhg.hgpage.wms.service;

import com.jhg.hgpage.contract.StockReplenishedHandler;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.wms.domain.PurchaseOrder;
import com.jhg.hgpage.wms.domain.PurchaseOrderItem;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.catalog.ProductRepository;
import com.jhg.hgpage.wms.repository.PurchaseOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseOrderService {

    private final ProductRepository productRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
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

        purchaseOrder.receive();

        // 입고로 가용분이 생겼음을 통지한다(백오더 승격은 OMS 구현체가 처리)
        List<Long> productIds = purchaseOrder.getItems().stream()
                .map(item -> item.getProduct().getId())
                .toList();
        stockReplenishedHandler.onReplenished(productIds);

        return purchaseOrder.getId();
    }

    public List<PurchaseOrder> findAllWithItems() {
        return purchaseOrderRepository.findAllWithItems();
    }
}
