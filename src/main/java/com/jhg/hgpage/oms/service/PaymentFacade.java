package com.jhg.hgpage.oms.service;

import com.jhg.hgpage.oms.domain.Address;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentFacade {

    private final CheckoutService checkoutService;
    private final PaymentService paymentService;
    private final PaymentApprovalProcessor approvalProcessor;
    private final OrderCancellationService cancellationService;

    public Long checkout(Long memberId, Address address, List<OrderService.OrderLine> lines, boolean fromCart) {
        return checkoutService.createPending(memberId, address, lines, fromCart).orderId();
    }

    public void startPayment(Long orderId, Long memberId) {
        approvalProcessor.process(paymentService.startPayment(orderId, memberId));
    }

    public void retryPayment(Long orderId, Long memberId) {
        approvalProcessor.process(paymentService.retryPayment(orderId, memberId));
    }

    public OrderCancellationService.CancellationOutcome cancelOrder(Long orderId, Long memberId) {
        return cancellationService.request(orderId, memberId).outcome();
    }
}
