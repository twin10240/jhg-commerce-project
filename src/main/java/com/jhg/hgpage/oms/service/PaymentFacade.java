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
        CheckoutService.CheckoutResult result = checkoutService.createPending(memberId, address, lines, fromCart);
        approvalProcessor.process(result.attemptId());
        return result.orderId();
    }

    public void retryPayment(Long orderId, Long memberId) {
        approvalProcessor.process(paymentService.retryPayment(orderId, memberId));
    }

    public boolean cancelOrder(Long orderId, Long memberId) {
        return cancellationService.request(orderId, memberId).paid();
    }
}
