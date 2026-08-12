package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.CustomerReturnItem;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import com.jhg.hgpage.oms.repository.CustomerReturnRepository;
import com.jhg.hgpage.oms.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomerReturnService {

    private final OrderRepository orderRepository;
    private final CustomerReturnRepository customerReturnRepository;

    @Transactional
    public Long request(Long orderId, Long memberId, String reason, List<ReturnLine> lines) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order", orderId));
        requireOwner(order, memberId);
        if (order.getDelivery().getStatus() != DeliveryStatus.DELIVERED) {
            throw new IllegalArgumentException("배송 완료 주문만 반품할 수 있습니다.");
        }

        String normalizedReason = normalizeReason(reason);
        Map<Long, ReturnLine> normalizedLines = normalizeLines(lines);
        Map<Long, OrderItem> orderItems = order.getOrderItems().stream()
                .collect(Collectors.toMap(OrderItem::getId, Function.identity()));
        normalizedLines.keySet().forEach(orderItemId -> {
            if (!orderItems.containsKey(orderItemId)) {
                throw new IllegalArgumentException("주문에 없는 품목입니다.");
            }
        });

        Map<Long, Integer> usedQuantities = usedQuantities(orderId);
        List<CustomerReturn.RequestItem> requestItems = normalizedLines.values().stream()
                .map(line -> {
                    OrderItem orderItem = orderItems.get(line.orderItemId());
                    if ((long) usedQuantities.getOrDefault(line.orderItemId(), 0) + line.quantity()
                            > orderItem.getCount()) {
                        throw new IllegalArgumentException("반품 가능 수량을 초과했습니다.");
                    }
                    return new CustomerReturn.RequestItem(orderItem, line.quantity());
                })
                .toList();

        CustomerReturn customerReturn = CustomerReturn.create(
                order, UUID.randomUUID(), normalizedReason, requestItems);
        return customerReturnRepository.save(customerReturn).getId();
    }

    @Transactional(readOnly = true)
    public Submission pendingSubmission(Long returnId) {
        CustomerReturn customerReturn = find(returnId);
        if (customerReturn.getStatus() != CustomerReturnStatus.PENDING_SUBMISSION) {
            throw new IllegalStateException("WMS 접수 대기 상태가 아닙니다.");
        }
        return new Submission(customerReturn.getId(), customerReturn.getRequestKey(),
                customerReturn.getOrder().getId(), customerReturn.getReason(),
                customerReturn.getItems().stream()
                        .map(item -> new SubmissionItem(item.getOrderItem().getId(),
                                item.getOrderItem().getProduct().getId(), item.getRequestedQuantity()))
                        .toList());
    }

    @Transactional
    public void markRequested(Long returnId, Long rmaId) {
        findForUpdate(returnId).markRequested(rmaId);
    }

    @Transactional
    public void markSubmissionFailed(Long returnId, String failureCode) {
        CustomerReturn customerReturn = findForUpdate(returnId);
        if (customerReturn.getStatus() == CustomerReturnStatus.PENDING_SUBMISSION) {
            customerReturn.failSubmission(failureCode);
        }
    }

    @Transactional(readOnly = true)
    public CustomerReturn findOwned(Long returnId, Long memberId) {
        CustomerReturn customerReturn = find(returnId);
        requireOwner(customerReturn.getOrder(), memberId);
        return customerReturn;
    }

    @Transactional(readOnly = true)
    public List<CustomerReturn> findForOwnedOrder(Long orderId, Long memberId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order", orderId));
        requireOwner(order, memberId);
        return customerReturnRepository.findDetailedByOrderId(orderId);
    }

    @Transactional(readOnly = true)
    public List<Long> pendingSubmissionIds() {
        return customerReturnRepository.findDetailedByStatusIn(List.of(CustomerReturnStatus.PENDING_SUBMISSION))
                .stream().map(CustomerReturn::getId).toList();
    }

    @Transactional(readOnly = true)
    public List<ActiveReturn> activeReturns() {
        return customerReturnRepository.findDetailedByStatusIn(
                        List.of(CustomerReturnStatus.REQUESTED, CustomerReturnStatus.RECEIVED))
                .stream()
                .filter(customerReturn -> customerReturn.getRmaId() != null)
                .map(customerReturn -> new ActiveReturn(customerReturn.getId(), customerReturn.getRmaId()))
                .toList();
    }

    private Map<Long, Integer> usedQuantities(Long orderId) {
        Map<Long, Integer> used = new HashMap<>();
        for (CustomerReturn customerReturn : customerReturnRepository.findDetailedByOrderId(orderId)) {
            for (CustomerReturnItem item : customerReturn.getItems()) {
                int quantity = switch (customerReturn.getStatus()) {
                    case COMPLETED -> item.getAcceptedQuantity();
                    case PENDING_SUBMISSION, REQUESTED, RECEIVED -> item.getRequestedQuantity();
                    case CANCELLED, SUBMISSION_FAILED -> 0;
                };
                used.merge(item.getOrderItem().getId(), quantity, Integer::sum);
            }
        }
        return used;
    }

    private Map<Long, ReturnLine> normalizeLines(List<ReturnLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("반품 품목은 필수입니다.");
        }
        Map<Long, ReturnLine> normalized = new LinkedHashMap<>();
        for (ReturnLine line : lines) {
            if (line == null || line.orderItemId() == null || line.quantity() <= 0) {
                throw new IllegalArgumentException("반품 품목과 수량은 필수입니다.");
            }
            if (normalized.put(line.orderItemId(), line) != null) {
                throw new IllegalArgumentException("같은 주문 품목은 한 번만 요청할 수 있습니다.");
            }
        }
        return normalized;
    }

    private String normalizeReason(String reason) {
        if (reason == null) {
            throw new IllegalArgumentException("반품 사유는 필수입니다.");
        }
        String normalized = reason.trim();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw new IllegalArgumentException("반품 사유는 1자 이상 500자 이하여야 합니다.");
        }
        return normalized;
    }

    private CustomerReturn find(Long returnId) {
        return customerReturnRepository.findDetailedById(returnId)
                .orElseThrow(() -> new EntityNotFoundException("CustomerReturn", returnId));
    }

    private CustomerReturn findForUpdate(Long returnId) {
        return customerReturnRepository.findDetailedByIdForUpdate(returnId)
                .orElseThrow(() -> new EntityNotFoundException("CustomerReturn", returnId));
    }

    private void requireOwner(Order order, Long memberId) {
        if (!order.getMember().getId().equals(memberId)) {
            throw new EntityNotFoundException("Order", order.getId());
        }
    }

    public record ReturnLine(Long orderItemId, int quantity) {}

    public record Submission(Long returnId, UUID requestKey, Long orderId, String reason,
                             List<SubmissionItem> items) {}

    public record SubmissionItem(Long orderItemId, Long productId, int quantity) {}

    public record ActiveReturn(Long returnId, Long rmaId) {}
}
