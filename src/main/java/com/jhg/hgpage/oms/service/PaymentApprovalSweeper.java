package com.jhg.hgpage.oms.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class PaymentApprovalSweeper {

    private final PaymentService paymentService;
    private final PaymentApprovalProcessor processor;

    @Scheduled(fixedDelayString = "${payments.sweep-delay:5s}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        paymentService.recoverStaleApprovals(now.minusMinutes(5), now);
        paymentService.findDueApprovalAttemptIds(now).forEach(processor::process);
    }
}
