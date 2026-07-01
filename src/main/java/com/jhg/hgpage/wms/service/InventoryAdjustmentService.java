package com.jhg.hgpage.wms.service;

import com.jhg.hgpage.contract.StockReplenishedHandler;
import com.jhg.hgpage.wms.adapter.WmsInventoryAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 관리자 재고 수동 조정 + 재고 증가 시 백오더 승격 트리거.
 * 실제 재고 변경은 WmsInventoryAdapter를 통해 WMS HTTP로 위임한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryAdjustmentService {

    private final WmsInventoryAdapter wmsInventoryAdapter;
    private final StockReplenishedHandler stockReplenishedHandler;

    public int adjust(Long productId, int delta, String reason) {
        int adjusted = wmsInventoryAdapter.adjust(productId, delta, reason);
        log.info("재고 조정: productId={}, delta={}, adjusted={}, reason={}", productId, delta, adjusted, reason);
        if (delta > 0) {
            stockReplenishedHandler.onReplenished(List.of(productId));
        }
        return adjusted;
    }
}
