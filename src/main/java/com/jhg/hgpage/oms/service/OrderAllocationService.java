package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.repository.OrderRepositoryQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderAllocationService {

    private static final String STALE_PROCESSING = "STALE_PROCESSING";

    private final OrderRepository orderRepository;
    private final OrderRepositoryQuery orderRepositoryQuery;
    private final RetrySchedule retrySchedule;

    @Transactional
    public Optional<AllocationCommand> claim(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (order == null || !isDue(order, now)) {
            return Optional.empty();
        }
        if (order.getStatus() == OrderStatus.ALLOCATION_PENDING) {
            order.claimAllocation(now);
        } else {
            claimCancelledAllocation(order, now);
        }
        return Optional.of(new AllocationCommand(
                order.getAllocationAttemptCount(), Map.copyOf(order.quantitiesByProductId())));
    }

    @Transactional
    public void complete(Long orderId, int attemptNumber, boolean reserved) {
        Order order = activeAllocation(orderId, attemptNumber);
        if (order == null) {
            return;
        }
        if (order.getStatus() == OrderStatus.CANCEL_REQUESTED) {
            order.resolveCancellationRelease(reserved);
        } else if (reserved) {
            order.markOrdered();
        } else {
            order.markBackordered();
        }
        clearWork(order);
    }

    @Transactional
    public void retryOrReview(Long orderId, int attemptNumber, String failureCode) {
        Order order = activeAllocation(orderId, attemptNumber);
        if (order == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        retrySchedule.nextAttemptAt(order.getAllocationAttemptCount(), now)
                .ifPresentOrElse(
                        next -> retryAt(order, next, failureCode),
                        () -> review(order, failureCode));
    }

    @Transactional
    public void manualReview(Long orderId, int attemptNumber, String failureCode) {
        Order order = activeAllocation(orderId, attemptNumber);
        if (order != null) {
            review(order, failureCode);
        }
    }

    @Transactional
    public void recoverStaleAllocations(LocalDateTime staleBefore, LocalDateTime now) {
        for (Long orderId : orderRepositoryQuery.findStaleAllocationOrderIds(staleBefore)) {
            Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
            if (order == null || order.getAllocationProcessingAt() == null
                    || order.getAllocationProcessingAt().isAfter(staleBefore)) {
                continue;
            }
            if (order.getAllocationAttemptCount() >= 5) {
                review(order, STALE_PROCESSING);
            } else {
                retryAt(order, now, STALE_PROCESSING);
            }
        }
    }

    public List<Long> findDueAllocationOrderIds(LocalDateTime now) {
        return orderRepositoryQuery.findDueAllocationOrderIds(now);
    }

    private boolean isDue(Order order, LocalDateTime now) {
        if (order.getNextAllocationAttemptAt() == null
                || order.getNextAllocationAttemptAt().isAfter(now)) {
            return false;
        }
        return order.getStatus() == OrderStatus.ALLOCATION_PENDING
                || order.getStatus() == OrderStatus.CANCEL_REQUESTED
                && order.getCancellationReleaseRequired() == null
                && order.getAllocationAttemptCount() > 0;
    }

    private Order activeAllocation(Long orderId, int attemptNumber) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getAllocationProcessingAt() == null
                || order.getAllocationAttemptCount() != attemptNumber) {
            return null;
        }
        if (order.getStatus() == OrderStatus.ALLOCATION_PROCESSING) {
            return order;
        }
        return order.getStatus() == OrderStatus.CANCEL_REQUESTED
                && order.getCancellationReleaseRequired() == null ? order : null;
    }

    private void claimCancelledAllocation(Order order, LocalDateTime now) {
        order.setAllocationAttemptCount(order.getAllocationAttemptCount() + 1);
        order.setNextAllocationAttemptAt(null);
        order.setAllocationProcessingAt(now);
    }

    private void retryAt(Order order, LocalDateTime nextAttemptAt, String failureCode) {
        if (order.getStatus() == OrderStatus.ALLOCATION_PROCESSING) {
            order.retryAllocation(nextAttemptAt, failureCode);
            return;
        }
        order.setNextAllocationAttemptAt(nextAttemptAt);
        order.setAllocationFailureCode(failureCode);
        order.setAllocationProcessingAt(null);
    }

    private void review(Order order, String failureCode) {
        if (order.getStatus() == OrderStatus.ALLOCATION_PROCESSING) {
            order.markAllocationReview(failureCode);
            return;
        }
        order.setAllocationFailureCode(failureCode);
        order.setNextAllocationAttemptAt(null);
        order.setAllocationProcessingAt(null);
    }

    private void clearWork(Order order) {
        order.setNextAllocationAttemptAt(null);
        order.setAllocationFailureCode(null);
        order.setAllocationProcessingAt(null);
    }

    public record AllocationCommand(int attemptNumber, Map<Long, Integer> quantities) {
    }
}
