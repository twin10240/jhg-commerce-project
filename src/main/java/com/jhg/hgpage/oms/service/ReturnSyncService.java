package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.contract.ReturnPort.ResultItem;
import com.jhg.hgpage.contract.ReturnPort.ReturnResult;
import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.CustomerReturnItem;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.domain.enums.ReturnDisposition;
import com.jhg.hgpage.oms.repository.CustomerReturnRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReturnSyncService {

    private final CustomerReturnRepository customerReturnRepository;
    private final RefundService refundService;

    @Transactional
    public void apply(ReturnResult result) {
        require(result != null && result.requestKey() != null && result.rmaId() != null && result.rmaId() > 0
                && result.orderId() != null && result.orderId() > 0 && result.items() != null);
        CustomerReturnStatus target = status(result.status());
        CustomerReturn customerReturn = customerReturnRepository.findDetailedByRequestKeyForUpdate(result.requestKey())
                .orElseThrow(ReturnContractMismatchException::new);
        require((customerReturn.getRmaId() == null || customerReturn.getRmaId().equals(result.rmaId()))
                && customerReturn.getOrder().getId().equals(result.orderId()));
        require(customerReturnRepository.findByRmaId(result.rmaId())
                .map(owner -> owner.getId().equals(customerReturn.getId())).orElse(true));

        Map<Long, ResultItem> results = validateItems(customerReturn, result.items(), target);
        CustomerReturnStatus current = customerReturn.getStatus();
        require(current != CustomerReturnStatus.SUBMISSION_FAILED);
        if (isRegression(current, target)) {
            log.warn("이전 RMA 상태 무시: returnId={}, requestKey={}, rmaId={}, status={}",
                    customerReturn.getId(), result.requestKey(), result.rmaId(), result.status());
            return;
        }
        require(isLegal(current, target));
        if (current == target) {
            if (target == CustomerReturnStatus.COMPLETED) {
                require(matchesAppliedResults(customerReturn, results));
            }
            if (customerReturn.getRmaId() == null) {
                customerReturn.markRequested(result.rmaId());
            }
            return;
        }

        try {
            customerReturn.markRequested(result.rmaId());
            switch (target) {
                case REQUESTED -> { }
                case RECEIVED -> customerReturn.markReceived();
                case COMPLETED -> customerReturn.complete(customerReturn.getItems().stream()
                        .map(item -> toDomainResult(item, results.get(item.getOrderItem().getId())))
                        .toList());
                case CANCELLED -> customerReturn.cancel();
                default -> throw new ReturnContractMismatchException();
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ReturnContractMismatchException();
        }
        if (target == CustomerReturnStatus.COMPLETED) {
            refundService.requestReturnRefund(customerReturn);
        }
        customerReturnRepository.flush();
    }

    private Map<Long, ResultItem> validateItems(CustomerReturn customerReturn, List<ResultItem> remoteItems,
                                                 CustomerReturnStatus status) {
        require(remoteItems.size() == customerReturn.getItems().size());
        Map<Long, CustomerReturnItem> localItems = new HashMap<>();
        customerReturn.getItems().forEach(item -> localItems.put(item.getOrderItem().getId(), item));
        Map<Long, ResultItem> results = new HashMap<>();
        for (ResultItem result : remoteItems) {
            require(result != null && result.orderItemId() != null && result.productId() != null
                    && results.put(result.orderItemId(), result) == null);
            CustomerReturnItem local = localItems.get(result.orderItemId());
            require(local != null && local.getOrderItem().getProduct().getId().equals(result.productId())
                    && local.getRequestedQuantity() == result.requestedQuantity());
            validateOutcome(local, result, status);
        }
        require(results.size() == localItems.size());
        return results;
    }

    private void validateOutcome(CustomerReturnItem local, ResultItem result, CustomerReturnStatus status) {
        if (status == CustomerReturnStatus.REQUESTED || status == CustomerReturnStatus.RECEIVED
                || status == CustomerReturnStatus.CANCELLED) {
            require(result.acceptedQuantity() == 0 && result.disposition() == null);
            return;
        }
        require(status == CustomerReturnStatus.COMPLETED
                && result.acceptedQuantity() >= 0
                && result.acceptedQuantity() <= local.getRequestedQuantity());
        ReturnDisposition disposition = disposition(result.disposition());
        require(result.acceptedQuantity() == 0
                ? disposition == ReturnDisposition.REJECTED
                : disposition == ReturnDisposition.RESTOCKED || disposition == ReturnDisposition.DISPOSED);
    }

    private CustomerReturn.ResultItem toDomainResult(CustomerReturnItem local, ResultItem result) {
        return new CustomerReturn.ResultItem(local.getOrderItem().getId(), result.acceptedQuantity(),
                disposition(result.disposition()));
    }

    private boolean matchesAppliedResults(CustomerReturn customerReturn, Map<Long, ResultItem> results) {
        for (CustomerReturnItem local : customerReturn.getItems()) {
            ResultItem result = results.get(local.getOrderItem().getId());
            if (local.getAcceptedQuantity() == null || !local.getAcceptedQuantity().equals(result.acceptedQuantity())
                    || local.getDisposition() != disposition(result.disposition())) {
                return false;
            }
        }
        return true;
    }

    private boolean isRegression(CustomerReturnStatus current, CustomerReturnStatus target) {
        return current == CustomerReturnStatus.RECEIVED && target == CustomerReturnStatus.REQUESTED
                || current == CustomerReturnStatus.COMPLETED
                && (target == CustomerReturnStatus.REQUESTED || target == CustomerReturnStatus.RECEIVED)
                || current == CustomerReturnStatus.CANCELLED && target == CustomerReturnStatus.REQUESTED;
    }

    private boolean isLegal(CustomerReturnStatus current, CustomerReturnStatus target) {
        return switch (current) {
            case PENDING_SUBMISSION -> true;
            case REQUESTED -> true;
            case RECEIVED -> target == CustomerReturnStatus.RECEIVED || target == CustomerReturnStatus.COMPLETED;
            case COMPLETED -> target == CustomerReturnStatus.COMPLETED;
            case CANCELLED -> target == CustomerReturnStatus.CANCELLED;
            case SUBMISSION_FAILED -> false;
        };
    }

    private CustomerReturnStatus status(String status) {
        try {
            CustomerReturnStatus parsed = CustomerReturnStatus.valueOf(status);
            require(parsed == CustomerReturnStatus.REQUESTED || parsed == CustomerReturnStatus.RECEIVED
                    || parsed == CustomerReturnStatus.COMPLETED || parsed == CustomerReturnStatus.CANCELLED);
            return parsed;
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw new ReturnContractMismatchException();
        }
    }

    private ReturnDisposition disposition(String disposition) {
        try {
            return ReturnDisposition.valueOf(disposition);
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw new ReturnContractMismatchException();
        }
    }

    private void require(boolean valid) {
        if (!valid) throw new ReturnContractMismatchException();
    }

    public static final class ReturnContractMismatchException extends RuntimeException {
        public ReturnContractMismatchException() {
            super("WMS return contract mismatch");
        }
    }
}
