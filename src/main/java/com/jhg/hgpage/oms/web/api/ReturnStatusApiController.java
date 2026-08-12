package com.jhg.hgpage.oms.web.api;

import com.jhg.hgpage.contract.ReturnPort.ReturnResult;
import com.jhg.hgpage.oms.service.ReturnSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReturnStatusApiController {

    private final ReturnSyncService returnSyncService;

    @PostMapping("/api/return-status-events")
    public ResponseEntity<Void> receive(@RequestBody(required = false) ReturnResult result) {
        if (result == null || result.rmaId() == null || result.requestKey() == null || result.orderId() == null
                || result.status() == null || result.items() == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            returnSyncService.apply(result);
            return ResponseEntity.ok().build();
        } catch (ReturnSyncService.ReturnContractMismatchException exception) {
            return ResponseEntity.status(409).build();
        }
    }
}
