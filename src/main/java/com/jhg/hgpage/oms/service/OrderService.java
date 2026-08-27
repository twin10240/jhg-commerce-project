package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.contract.InventoryPort;
import com.jhg.hgpage.contract.InventoryQueryPort;
import com.jhg.hgpage.oms.domain.*;
import com.jhg.hgpage.oms.dto.AdminOrderDto;
import com.jhg.hgpage.oms.dto.OrderDetailDto;
import com.jhg.hgpage.oms.dto.OrderDto;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.repository.CustomerReturnRepository;
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
    private final CustomerReturnRepository customerReturnRepository;
    private final OrderCancellationService cancellationService;
    private final InventoryPort inventoryPort;
    private final InventoryQueryPort inventoryQueryPort;

    public List<Order> findAllOrders() {
        return orderRepository.findAll();
    }

    public OrderDetailDto findOrderDetail(Long orderId, Long memberId) {
        return OrderDetailDto.from(findOwnedOrder(orderId, memberId),
                orderRepositoryQuery.findPaymentByOrderId(orderId).orElse(null));
    }

    // 관리자 배송 관리 목록
    public List<AdminOrderDto> findAllForAdmin() {
        List<Order> orders = orderRepositoryQuery.findAllForAdmin();
        Map<Long, Payment> payments = orderRepositoryQuery.findPaymentsByOrderIds(
                        orders.stream().map(Order::getId).toList()).stream()
                .collect(Collectors.toMap(payment -> payment.getOrder().getId(), payment -> payment));
        var cancellationReviewOrderIds = new java.util.HashSet<>(
                orderRepositoryQuery.findCancellationAllocationReviewOrderIds());
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
                .map(order -> AdminOrderDto.from(order, availability, payments.get(order.getId()),
                        cancellationReviewOrderIds.contains(order.getId())))
                .toList();
    }

    @Transactional
    public void shipOrder(Long orderId) {
        Order order = findOrder(orderId);
        // 상태 전이는 도메인이, 실물 차감은 WMS 포트가 수행한다(가드 통과 후에만 출고).
        order.ship();
        InventoryPort.ShipmentResult shipment = inventoryPort.shipAll(
                order.getId(), order.quantitiesByProductId());
        order.getDelivery().recordShipment(shipment.carrierCode(), shipment.carrierName(),
                shipment.trackingNumber(), shipment.issuedAt());
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
        Map<Long, Payment> payments = orderRepositoryQuery.findPaymentsByOrderIds(
                        orders.stream().map(Order::getId).toList()).stream()
                .collect(Collectors.toMap(payment -> payment.getOrder().getId(), payment -> payment));
        Map<Long, List<CustomerReturn>> returns = (orders.isEmpty() ? List.<CustomerReturn>of()
                : customerReturnRepository.findDetailedByOrderIdIn(
                        orders.stream().map(Order::getId).toList())).stream()
                .filter(customerReturn -> customerReturn.getStatus() != CustomerReturnStatus.CANCELLED
                        && customerReturn.getStatus() != CustomerReturnStatus.REJECTED)
                .collect(Collectors.groupingBy(customerReturn -> customerReturn.getOrder().getId()));
        return orders.stream()
                .map(order -> {
                    OrderDto dto = OrderDto.from(order, payments.get(order.getId()));
                    String returnStatus = returnStatusLabel(order, returns.get(order.getId()));
                    if (returnStatus != null) {
                        dto.setOrderStatusLabel(returnStatus);
                    }
                    return dto;
                })
                .collect(Collectors.toList());
    }

    private String returnStatusLabel(Order order, List<CustomerReturn> returns) {
        if (returns == null || returns.isEmpty()) {
            return null;
        }
        CustomerReturn latest = returns.get(returns.size() - 1);
        int returnQuantity = returns.stream()
                .mapToInt(customerReturn -> customerReturn.getItems().stream()
                        .mapToInt(item -> customerReturn.getStatus() == CustomerReturnStatus.COMPLETED
                                ? item.getAcceptedQuantity() : item.getRequestedQuantity())
                        .sum())
                .sum();
        boolean partial = returnQuantity < order.getOrderItems().stream().mapToInt(OrderItem::getCount).sum();
        if (partial) {
            return switch (latest.getStatus()) {
                case COMPLETED -> "일부 반품 완료";
                case SUBMISSION_FAILED -> "일부 반품 접수 실패";
                default -> "일부 반품 진행 중";
            };
        }
        return switch (latest.getStatus()) {
            case PENDING_APPROVAL -> "반품 승인 대기";
            case PENDING_SUBMISSION -> "WMS 전송 중";
            case SUBMISSION_FAILED -> "반품 접수 실패";
            case REQUESTED -> "반품 접수";
            case RECEIVED -> "창고 입고";
            case COMPLETED -> "반품 완료";
            default -> null;
        };
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
