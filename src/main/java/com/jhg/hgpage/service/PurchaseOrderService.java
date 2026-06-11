package com.jhg.hgpage.service;

import com.jhg.hgpage.domain.Product;
import com.jhg.hgpage.domain.PurchaseOrder;
import com.jhg.hgpage.domain.PurchaseOrderItem;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.repository.ProductRepository;
import com.jhg.hgpage.repository.PurchaseOrderRepository;
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

        return purchaseOrder.getId();
    }

    public List<PurchaseOrder> findAllWithItems() {
        return purchaseOrderRepository.findAllWithItems();
    }
}
