package com.jhg.hgpage.service;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.catalog.ProductRepository;
import com.jhg.hgpage.contract.InventoryPort;
import com.jhg.hgpage.contract.PaymentGateway;
import com.jhg.hgpage.contract.ReturnPort.ReturnResult;
import com.jhg.hgpage.contract.ReturnPort.ResultItem;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.domain.enums.PaymentStatus;
import com.jhg.hgpage.oms.domain.enums.RefundStatus;
import com.jhg.hgpage.oms.repository.CustomerReturnRepository;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.repository.PaymentRepository;
import com.jhg.hgpage.oms.repository.RefundRequestRepository;
import com.jhg.hgpage.oms.service.AllocationProcessor;
import com.jhg.hgpage.oms.service.CustomerReturnService;
import com.jhg.hgpage.oms.service.OrderAllocationService;
import com.jhg.hgpage.oms.service.OrderCancellationService;
import com.jhg.hgpage.oms.service.OrderService;
import com.jhg.hgpage.oms.service.PaymentFacade;
import com.jhg.hgpage.oms.service.RefundProcessor;
import com.jhg.hgpage.oms.service.ReturnSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static com.jhg.hgpage.contract.PaymentGateway.GatewayOutcome.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "payments.sweep-delay=1h", "allocation.sweep-delay=1h",
        "refunds.sweep-delay=1h", "cancellations.sweep-delay=1h",
        "returns.sweep-delay=1h", "backorder.sweep-delay=1h"
})
class PaymentWorkflowIntegrationTest {

    @Autowired PaymentFacade paymentFacade;
    @Autowired AllocationProcessor allocationProcessor;
    @Autowired OrderAllocationService orderAllocationService;
    @Autowired OrderCancellationService cancellationService;
    @Autowired RefundProcessor refundProcessor;
    @Autowired CustomerReturnService customerReturnService;
    @Autowired ReturnSyncService returnSyncService;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired RefundRequestRepository refundRequestRepository;
    @Autowired CustomerReturnRepository customerReturnRepository;
    @Autowired ProductRepository productRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @MockitoBean PaymentGateway paymentGateway;
    @MockitoBean InventoryPort inventoryPort;

    private Long memberId;
    private Product product;

    @BeforeEach
    void setUp() {
        memberId = transactionTemplate.execute(status -> Long.valueOf(1));
        product = productRepository.findAll().get(0);
        when(paymentGateway.approve(any())).thenReturn(new PaymentGateway.ApprovalResult(
                SUCCESS, "approval", null, null));
        when(paymentGateway.refund(any())).thenReturn(new PaymentGateway.RefundResult(
                SUCCESS, "refund", null, null));
    }

    @Test
    void 결제승인_뒤_할당성공은_ORDER로_영속된다() {
        when(inventoryPort.reserveAll(any(), any())).thenReturn(true);

        Long orderId = checkout(2);
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.ALLOCATION_PENDING);
        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);

        allocationProcessor.process(orderId);

        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.ORDER);
    }

    @Test
    void 결제승인_뒤_재고부족은_BACKORDERED로_영속된다() {
        when(inventoryPort.reserveAll(any(), any())).thenReturn(false);

        Long orderId = checkout(2);
        allocationProcessor.process(orderId);

        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.BACKORDERED);
        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getPaidAmount()).isEqualTo(product.getPrice() * 2);
    }

    @Test
    void 유료_BACKORDERED_취소는_전액환불을_한번만_완료한다() {
        when(inventoryPort.reserveAll(any(), any())).thenReturn(false);
        Long orderId = checkout(2);
        allocationProcessor.process(orderId);

        cancellationService.request(orderId, memberId);
        Long refundId = refundRequestRepository.findAll().stream()
                .filter(refund -> refund.getSourceId().equals(orderId))
                .findFirst().orElseThrow().getId();
        refundProcessor.process(refundId);
        refundProcessor.process(refundId);

        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCEL);
        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow())
                .extracting("refundedAmount", "pendingRefundAmount", "status")
                .containsExactly(product.getPrice() * 2, 0, PaymentStatus.REFUNDED);
        assertThat(refundRequestRepository.findById(refundId).orElseThrow().getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        verify(paymentGateway, times(1)).refund(any());
    }

    @Test
    void 배송완료_주문의_부분반품승인은_부분환불을_한번만_완료한다() {
        when(inventoryPort.reserveAll(any(), any())).thenReturn(true);
        Long orderId = checkout(2);
        allocationProcessor.process(orderId);
        transactionTemplate.executeWithoutResult(status -> {
            Order persisted = orderRepository.findById(orderId).orElseThrow();
            persisted.ship();
            persisted.deliver();
        });

        Long orderItemId = transactionTemplate.execute(status -> orderRepository.findById(orderId).orElseThrow()
                .getOrderItems().get(0).getId());
        Long returnId = customerReturnService.request(orderId, memberId, "partial", List.of(
                new CustomerReturnService.ReturnLine(orderItemId, 2)));
        ReturnFixture returnFixture = transactionTemplate.execute(status -> {
            CustomerReturn customerReturn = customerReturnRepository.findDetailedById(returnId).orElseThrow();
            return new ReturnFixture(customerReturn.getRequestKey(),
                    customerReturn.getItems().get(0).getOrderItem().getId());
        });
        returnSyncService.apply(new ReturnResult(9001L, returnFixture.requestKey(), orderId, "COMPLETED", List.of(
                new ResultItem(returnFixture.orderItemId(), product.getId(), 2, 1, "RESTOCKED"))));
        Long refundId = refundRequestRepository.findAll().stream()
                .filter(refund -> refund.getSourceId().equals(returnId)).findFirst().orElseThrow().getId();
        refundProcessor.process(refundId);
        refundProcessor.process(refundId);

        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow())
                .extracting("paidAmount", "refundedAmount", "pendingRefundAmount", "status")
                .containsExactly(product.getPrice() * 2, product.getPrice(), 0, PaymentStatus.PARTIALLY_REFUNDED);
        verify(paymentGateway, times(1)).refund(any());
    }

    @Test
    void 재기동_뒤에도_미완료_할당작업은_발견된다() {
        Long orderId = checkout(1);

        assertThat(orderAllocationService.findDueAllocationOrderIds(java.time.LocalDateTime.now()))
                .contains(orderId);
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.ALLOCATION_PENDING);
    }

    private Long checkout(int quantity) {
        return paymentFacade.checkout(memberId, new Address("서울", "관악구", "500"),
                List.of(new OrderService.OrderLine(product.getId(), quantity)), false);
    }

    private Order order(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow();
    }

    private record ReturnFixture(java.util.UUID requestKey, Long orderItemId) {
    }
}
