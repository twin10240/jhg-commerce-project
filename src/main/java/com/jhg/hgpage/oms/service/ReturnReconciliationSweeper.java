package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.contract.ReturnPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReturnReconciliationSweeper {

    private final CustomerReturnService customerReturnService;
    private final ReturnSubmissionService returnSubmissionService;
    private final ReturnSyncService returnSyncService;
    private final ReturnPort returnPort;

    @Scheduled(fixedDelayString = "${returns.sweep-delay:60s}",
               initialDelayString = "${returns.sweep-delay:60s}")
    public void sweep() {
        try {
            sweepSubmissions();
        } catch (RuntimeException exception) {
            log.warn("RMA 접수 대상 스캔 실패", exception);
        }
        try {
            sweepActiveReturns();
        } catch (RuntimeException exception) {
            log.warn("RMA 상태 대상 스캔 실패", exception);
        }
    }

    private void sweepSubmissions() {
        for (Long returnId : customerReturnService.pendingSubmissionIds()) {
            try {
                returnSubmissionService.submit(returnId);
            } catch (RuntimeException exception) {
                log.warn("RMA 접수 스윕 실패: returnId={}", returnId, exception);
            }
        }
    }

    private void sweepActiveReturns() {
        for (CustomerReturnService.ActiveReturn active : customerReturnService.activeReturns()) {
            try {
                returnSyncService.apply(returnPort.find(active.rmaId()));
            } catch (RuntimeException exception) {
                log.warn("RMA 상태 스윕 실패: returnId={}, rmaId={}",
                        active.returnId(), active.rmaId(), exception);
            }
        }
    }
}
