package com.jhg.hgpage.wms.service;

import com.jhg.hgpage.contract.InventoryPort;
import com.jhg.hgpage.contract.InventoryQueryPort;
import com.jhg.hgpage.wms.domain.Inventory;
import com.jhg.hgpage.wms.domain.Reservation;
import com.jhg.hgpage.wms.domain.enums.ReservationStatus;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.wms.dto.InventoryRow;
import com.jhg.hgpage.wms.repository.InventoryRepository;
import com.jhg.hgpage.wms.repository.ReservationRepository;
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
    private final ReservationRepository reservationRepository;

    /** 관리자 재고화면 행 조립: WMS 재고의 productId + 보유수량만(상품명·가격은 OMS catalog 소관). */
    public List<InventoryRow> findInventoryRows() {
        return inventoryRepository.findAll().stream()
                .map(inv -> new InventoryRow(inv.getProductId(), inv.getOnHandQty()))
                .sorted(Comparator.comparing(InventoryRow::productId))
                .toList();
    }

    @Override
    public Map<Long, Integer> availableByProductIds(Collection<Long> productIds) {
        return inventoryRepository.findByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(Inventory::getProductId, Inventory::getAvailableQty));
    }

    @Override
    @Transactional
    public boolean reserveAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        Reservation existing = reservationRepository.findByOrderId(orderId).orElse(null);
        if (existing != null) {
            // 멱등: 같은 주문은 한 번만 예약한다(해제된 게 아니면 예약 성공으로 간주).
            return existing.getStatus() != ReservationStatus.RELEASED;
        }

        Map<Long, Inventory> inventories = loadInventories(qtyByProductId.keySet());
        boolean allAvailable = qtyByProductId.entrySet().stream()
                .allMatch(e -> inventories.get(e.getKey()).getAvailableQty() >= e.getValue());
        if (!allAvailable) {
            return false; // 예약 기록을 남기지 않는다 → 입고/재시도 시 재예약(백오더 승격) 가능
        }
        qtyByProductId.forEach((productId, qty) -> inventories.get(productId).reserve(qty));
        reservationRepository.save(Reservation.reserve(orderId));
        return true;
    }

    @Override
    @Transactional
    public void shipAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        Reservation reservation = reservationRepository.findByOrderId(orderId).orElse(null);
        if (reservation == null) {
            throw new IllegalStateException("예약이 없어 출고할 수 없습니다. orderId=" + orderId);
        }
        if (reservation.getStatus() == ReservationStatus.SHIPPED) {
            return; // 멱등: 이미 출고됨
        }
        applyToInventories(qtyByProductId, Inventory::ship);
        reservation.ship();
    }

    @Override
    @Transactional
    public void releaseAll(Long orderId, Map<Long, Integer> qtyByProductId) {
        Reservation reservation = reservationRepository.findByOrderId(orderId).orElse(null);
        if (reservation == null || reservation.getStatus() == ReservationStatus.RELEASED) {
            return; // 멱등/방어: 풀 예약이 없으면 no-op
        }
        applyToInventories(qtyByProductId, Inventory::release);
        reservation.release();
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
