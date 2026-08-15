package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.contract.InventoryPort;
import com.jhg.hgpage.contract.InventoryQueryPort;
import com.jhg.hgpage.oms.domain.*;
import com.jhg.hgpage.oms.dto.AdminOrderDto;
import com.jhg.hgpage.oms.dto.OrderDetailDto;
import com.jhg.hgpage.oms.dto.OrderDto;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderRepositoryQuery orderRepositoryQuery;
    private final OrderCancellationService cancellationService;
    private final InventoryPort inventoryPort;
    private final InventoryQueryPort inventoryQueryPort;

    public List<Order> findAllOrders() {
        return orderRepository.findAll();
    }

    public OrderDetailDto findOrderDetail(Long orderId, Long memberId) {
        return OrderDetailDto.from(findOwnedOrder(orderId, memberId));
    }

    // 관리자 배송 관리 목록
    public List<AdminOrderDto> findAllForAdmin() {
        List<Order> orders = orderRepositoryQuery.findAllForAdmin();
        List<Long> backorderedProductIds = orders.stream()
                .filter(order -> order.getStatus() == OrderStatus.BACKORDERED)
                .flatMap(order -> order.getOrderItems().stream())
                .map(orderItem -> orderItem.getProduct().getId())
                .distinct()
                .toList();
        Map<Long, Integer> availability = backorderedProductIds.isEmpty()
                ? Map.of()
                : inventoryQueryPort.availableByProductIds(backorderedProductIds);
        return orders.stream()
                .map(order -> AdminOrderDto.from(order, availability))
                .toList();
    }

    @Transactional
    public void shipOrder(Long orderId) {
        Order order = findOrder(orderId);
        // 상태 전이는 도메인이, 실물 차감은 WMS 포트가 수행한다(가드 통과 후에만 출고).
        order.ship();
        inventoryPort.shipAll(order.getId(), order.quantitiesByProductId());
    }

    @Transactional
    public void deliverOrder(Long orderId) {
        findOrder(orderId).deliver();
    }

    @Transactional
    public void cancelOrder(Long orderId, Long memberId) {
        cancellationService.request(orderId, memberId);
    }

    // 본인 주문만 반환. 타인 주문은 존재 자체를 숨기기 위해 404(EntityNotFoundException)로 처리(IDOR 방지)
    private Order findOwnedOrder(Long orderId, Long memberId) {
        Order order = orderRepositoryQuery.findDetailById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order", orderId));
        if (!order.getMember().getId().equals(memberId)) {
            throw new EntityNotFoundException("Order", orderId);
        }
        return order;
    }

    private Order findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order", orderId));
    }

    public record OrderLine(Long productId, int quantity) {}

    public List<OrderDto> findOrders(Long memberId) {
        List<Order> orders = orderRepositoryQuery.findOrders(memberId);
        return orders.stream()
                .map(o -> new OrderDto(o.getId(), o.getStatus(), o.getDelivery().getStatus(),
                        o.getTotalPrice(), o.getOrderDate()))
                .collect(Collectors.toList());
    }

    public Map<Long, Integer> backorderDemandByProductId(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> demand = new HashMap<>();
        orderRepositoryQuery.findBackordersContaining(productIds)
                .forEach(order -> order.quantitiesByProductId()
                        .forEach((productId, quantity) -> demand.merge(productId, quantity, Integer::sum)));
        return demand;
    }
}
