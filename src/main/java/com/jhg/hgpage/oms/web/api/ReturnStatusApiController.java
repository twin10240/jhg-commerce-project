package com.jhg.hgpage.oms.web.api;

import com.jhg.hgpage.contract.ReturnPort.ReturnResult;
import com.jhg.hgpage.oms.service.ReturnSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ReturnStatusApiController {

    private final ReturnSyncService returnSyncService;

    @PostMapping("/api/return-status-events")
    public ResponseEntity<Void> receive(@RequestBody(required = false) ReturnStatusEvent event) {
        if (!valid(event)) {
            return ResponseEntity.badRequest().build();
        }
        try {
            returnSyncService.apply(event.toReturnResult());
            return ResponseEntity.ok().build();
        } catch (ReturnSyncService.ReturnContractMismatchException exception) {
            return ResponseEntity.status(409).build();
        }
    }

    private boolean valid(ReturnStatusEvent event) {
        return event != null && event.rmaId() != null && event.requestKey() != null && event.orderId() != null
                && event.status() != null && event.items() != null && !event.items().isEmpty()
                && event.items().stream().allMatch(this::valid);
    }

    private boolean valid(ReturnStatusItem item) {
        return item != null && item.orderItemId() != null && item.productId() != null
                && item.requestedQuantity() != null && item.acceptedQuantity() != null;
    }

    public record ReturnStatusEvent(Long rmaId, UUID requestKey, Long orderId, String status,
                                    List<ReturnStatusItem> items) {
        ReturnResult toReturnResult() {
            return new ReturnResult(rmaId, requestKey, orderId, status, items.stream()
                    .map(item -> new com.jhg.hgpage.contract.ReturnPort.ResultItem(item.orderItemId(),
                            item.productId(), item.requestedQuantity(), item.acceptedQuantity(), item.disposition()))
                    .toList());
        }
    }

    public record ReturnStatusItem(Long orderItemId, Long productId, Integer requestedQuantity,
                                   Integer acceptedQuantity, String disposition) {}
}
