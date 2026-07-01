package com.jhg.hgpage.wms.service;

import com.jhg.hgpage.contract.StockReplenishedHandler;
import com.jhg.hgpage.wms.adapter.WmsInventoryAdapter;
import com.jhg.hgpage.wms.domain.PurchaseOrder;
import com.jhg.hgpage.wms.domain.PurchaseOrderItem;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.wms.repository.PurchaseOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final WmsInventoryAdapter wmsInventoryAdapter;
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
                    return PurchaseOrderItem.create(line.productId(), line.quantity());
                })
                .toArray(PurchaseOrderItem[]::new);

        return purchaseOrderRepository.save(PurchaseOrder.create(memo, items)).getId();
    }

    @Transactional
    public Long receive(Long poId) {
        PurchaseOrder po = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new EntityNotFoundException("PurchaseOrder", poId));

        po.receive(); // 중복 입고 거부 — 이후 WMS 호출 실패 시 트랜잭션 롤백으로 po 상태 복원
        // ponytail: WMS HTTP 호출은 OMS 트랜잭션 밖. 첫 상품 성공 후 두 번째 실패 시 부분 반영 가능(S5 saga 후보)
        po.getItems().forEach(item ->
                wmsInventoryAdapter.adjust(item.getProductId(), item.getQuantity(), "PO #" + poId + " 입고")
        );

        stockReplenishedHandler.onReplenished(
                po.getItems().stream().map(PurchaseOrderItem::getProductId).toList()
        );
        return po.getId();
    }

    public List<PurchaseOrder> findAllWithItems() {
        return purchaseOrderRepository.findAllWithItems();
    }
}
