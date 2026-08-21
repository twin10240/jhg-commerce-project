package com.jhg.hgpage.oms.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class PaymentApprovalSweeper {

    private final PaymentService paymentService;
    private final PaymentApprovalProcessor processor;
    private final Duration processingTimeout;

    public PaymentApprovalSweeper(PaymentService paymentService, PaymentApprovalProcessor processor) {
        this(paymentService, processor, Duration.ofMinutes(5));
    }

    @Autowired
    public PaymentApprovalSweeper(PaymentService paymentService, PaymentApprovalProcessor processor,
                                  @Value("${payments.processing-timeout:5m}") Duration processingTimeout) {
        this.paymentService = paymentService;
        this.processor = processor;
        this.processingTimeout = processingTimeout;
    }

    @Scheduled(fixedDelayString = "${payments.sweep-delay:5s}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        paymentService.recoverStaleApprovals(now.minus(processingTimeout), now);
        paymentService.findDueApprovalAttemptIds(now).forEach(processor::process);
    }
}
