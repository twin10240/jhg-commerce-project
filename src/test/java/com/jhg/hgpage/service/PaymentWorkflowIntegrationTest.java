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
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.PaymentAttempt;
import com.jhg.hgpage.oms.domain.RefundRequest;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.domain.enums.PaymentAttemptStatus;
import com.jhg.hgpage.oms.domain.enums.PaymentStatus;
import com.jhg.hgpage.oms.domain.enums.RefundSourceType;
import com.jhg.hgpage.oms.domain.enums.RefundStatus;
import com.jhg.hgpage.oms.domain.enums.ReturnDisposition;
import com.jhg.hgpage.oms.repository.CustomerReturnRepository;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.repository.PaymentRepository;
import com.jhg.hgpage.oms.repository.RefundRequestRepository;
import com.jhg.hgpage.oms.service.AllocationProcessor;
import com.jhg.hgpage.oms.service.CustomerReturnService;
import com.jhg.hgpage.oms.service.OrderCancellationService;
import com.jhg.hgpage.oms.service.OrderService;
import com.jhg.hgpage.oms.service.PaymentFacade;
import com.jhg.hgpage.oms.service.RefundProcessor;
import com.jhg.hgpage.oms.service.ReturnSyncService;
import jakarta.persistence.EntityManager;
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
    @Autowired EntityManager em;

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
        when(inventoryPort.reserveAll(any(), any(), any())).thenReturn(true);

        Long orderId = checkout(2);
        assertThat(snapshot(orderId)).isEqualTo(new WorkflowSnapshot(
                OrderStatus.ALLOCATION_PENDING, DeliveryStatus.READY, 0,
                PaymentStatus.PAID, 20_000, 20_000, 0, 0,
                PaymentAttemptStatus.SUCCEEDED, 1, true, 0));

        allocationProcessor.process(orderId);

        assertThat(snapshot(orderId)).isEqualTo(new WorkflowSnapshot(
                OrderStatus.ORDER, DeliveryStatus.READY, 1,
                PaymentStatus.PAID, 20_000, 20_000, 0, 0,
                PaymentAttemptStatus.SUCCEEDED, 1, true, 0));
    }

    @Test
    void 결제승인_뒤_재고부족은_BACKORDERED로_영속된다() {
        when(inventoryPort.reserveAll(any(), any(), any())).thenReturn(false);

        Long orderId = checkout(2);
        assertThat(snapshot(orderId)).isEqualTo(new WorkflowSnapshot(
                OrderStatus.ALLOCATION_PENDING, DeliveryStatus.READY, 0,
                PaymentStatus.PAID, 20_000, 20_000, 0, 0,
                PaymentAttemptStatus.SUCCEEDED, 1, true, 0));
        allocationProcessor.process(orderId);

        assertThat(snapshot(orderId)).isEqualTo(new WorkflowSnapshot(
                OrderStatus.BACKORDERED, DeliveryStatus.READY, 1,
                PaymentStatus.PAID, 20_000, 20_000, 0, 0,
                PaymentAttemptStatus.SUCCEEDED, 1, true, 0));
    }

    @Test
    void 유료_BACKORDERED_취소는_전액환불을_한번만_완료한다() {
        when(inventoryPort.reserveAll(any(), any(), any())).thenReturn(false);
        Long orderId = checkout(2);
        allocationProcessor.process(orderId);
        assertThat(snapshot(orderId)).isEqualTo(new WorkflowSnapshot(
                OrderStatus.BACKORDERED, DeliveryStatus.READY, 1,
                PaymentStatus.PAID, 20_000, 20_000, 0, 0,
                PaymentAttemptStatus.SUCCEEDED, 1, true, 0));

        cancellationService.request(orderId, memberId);
        Long refundId = refundRequestRepository.findAll().stream()
                .filter(refund -> refund.getSourceId().equals(orderId))
                .findFirst().orElseThrow().getId();
        assertThat(snapshot(orderId)).isEqualTo(new WorkflowSnapshot(
                OrderStatus.CANCEL, DeliveryStatus.READY, 1,
                PaymentStatus.PAID, 20_000, 20_000, 20_000, 0,
                PaymentAttemptStatus.SUCCEEDED, 1, true, 1));
        assertThat(refundSnapshot(refundId)).isEqualTo(new RefundSnapshot(
                RefundStatus.PENDING, RefundSourceType.ORDER_CANCEL, orderId,
                20_000, 0, true));

        refundProcessor.process(refundId);
        refundProcessor.process(refundId);

        assertThat(snapshot(orderId)).isEqualTo(new WorkflowSnapshot(
                OrderStatus.CANCEL, DeliveryStatus.READY, 1,
                PaymentStatus.REFUNDED, 20_000, 20_000, 0, 20_000,
                PaymentAttemptStatus.SUCCEEDED, 1, true, 1));
        assertThat(refundSnapshot(refundId)).isEqualTo(new RefundSnapshot(
                RefundStatus.SUCCEEDED, RefundSourceType.ORDER_CANCEL, orderId,
                20_000, 1, true));
        verify(paymentGateway, times(1)).refund(any());
    }

    @Test
    void 배송완료_주문의_부분반품승인은_부분환불을_한번만_완료한다() {
        when(inventoryPort.reserveAll(any(), any(), any())).thenReturn(true);
        Long orderId = checkout(2);
        allocationProcessor.process(orderId);
        transactionTemplate.executeWithoutResult(status -> {
            Order persisted = orderRepository.findById(orderId).orElseThrow();
            persisted.ship();
            persisted.deliver();
        });
        assertThat(snapshot(orderId)).isEqualTo(new WorkflowSnapshot(
                OrderStatus.ORDER, DeliveryStatus.DELIVERED, 1,
                PaymentStatus.PAID, 20_000, 20_000, 0, 0,
                PaymentAttemptStatus.SUCCEEDED, 1, true, 0));

        Long orderItemId = transactionTemplate.execute(status -> orderRepository.findById(orderId).orElseThrow()
                .getOrderItems().get(0).getId());
        Long returnId = customerReturnService.request(orderId, memberId, "partial", List.of(
                new CustomerReturnService.ReturnLine(orderItemId, 2)));
        ReturnFixture returnFixture = transactionTemplate.execute(status -> {
            CustomerReturn customerReturn = customerReturnRepository.findDetailedById(returnId).orElseThrow();
            customerReturn.approve("admin@example.com");
            return new ReturnFixture(customerReturn.getRequestKey(),
                    customerReturn.getItems().get(0).getOrderItem().getId());
        });
        returnSyncService.apply(new ReturnResult(9001L, returnFixture.requestKey(), orderId, "COMPLETED", List.of(
                new ResultItem(returnFixture.orderItemId(), product.getId(), 2, 1, "RESTOCKED"))));
        Long refundId = refundRequestRepository.findAll().stream()
                .filter(refund -> refund.getSourceId().equals(returnId)).findFirst().orElseThrow().getId();
        assertThat(returnSnapshot(returnId)).isEqualTo(new ReturnSnapshot(
                CustomerReturnStatus.COMPLETED, 9001L, 2, 1, ReturnDisposition.RESTOCKED));
        assertThat(snapshot(orderId)).isEqualTo(new WorkflowSnapshot(
                OrderStatus.ORDER, DeliveryStatus.DELIVERED, 1,
                PaymentStatus.PAID, 20_000, 20_000, 10_000, 0,
                PaymentAttemptStatus.SUCCEEDED, 1, true, 1));
        assertThat(refundSnapshot(refundId)).isEqualTo(new RefundSnapshot(
                RefundStatus.PENDING, RefundSourceType.RETURN, returnId,
                10_000, 0, true));

        refundProcessor.process(refundId);
        refundProcessor.process(refundId);

        assertThat(snapshot(orderId)).isEqualTo(new WorkflowSnapshot(
                OrderStatus.ORDER, DeliveryStatus.DELIVERED, 1,
                PaymentStatus.PARTIALLY_REFUNDED, 20_000, 20_000, 0, 10_000,
                PaymentAttemptStatus.SUCCEEDED, 1, true, 1));
        assertThat(refundSnapshot(refundId)).isEqualTo(new RefundSnapshot(
                RefundStatus.SUCCEEDED, RefundSourceType.RETURN, returnId,
                10_000, 1, true));
        verify(paymentGateway, times(1)).refund(any());
    }

    private Long checkout(int quantity) {
        Long orderId = paymentFacade.checkout(memberId, new Address("서울", "관악구", "500"),
                List.of(new OrderService.OrderLine(product.getId(), quantity)), false);
        paymentFacade.startPayment(orderId, memberId);
        return orderId;
    }

    private WorkflowSnapshot snapshot(Long orderId) {
        return transactionTemplate.execute(status -> {
            Order order = orderRepository.findById(orderId).orElseThrow();
            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
            PaymentAttempt attempt = em.createQuery(
                            "select a from PaymentAttempt a where a.payment = :payment", PaymentAttempt.class)
                    .setParameter("payment", payment)
                    .getSingleResult();
            int refundCount = em.createQuery(
                            "select count(r) from RefundRequest r where r.payment = :payment", Long.class)
                    .setParameter("payment", payment)
                    .getSingleResult().intValue();
            return new WorkflowSnapshot(order.getStatus(), order.getDelivery().getStatus(),
                    order.getAllocationAttemptCount(), payment.getStatus(), payment.getOrderAmount(),
                    payment.getPaidAmount(), payment.getPendingRefundAmount(), payment.getRefundedAmount(),
                    attempt.getStatus(), attempt.getAttemptCount(), attempt.getGatewayTransactionId() != null,
                    refundCount);
        });
    }

    private RefundSnapshot refundSnapshot(Long refundId) {
        return transactionTemplate.execute(status -> {
            RefundRequest refund = refundRequestRepository.findById(refundId).orElseThrow();
            return new RefundSnapshot(refund.getStatus(), refund.getSourceType(), refund.getSourceId(),
                    refund.getAmount(), refund.getAttemptCount(), refund.getRequestKey() != null);
        });
    }

    private ReturnSnapshot returnSnapshot(Long returnId) {
        return transactionTemplate.execute(status -> {
            CustomerReturn customerReturn = customerReturnRepository.findDetailedById(returnId).orElseThrow();
            var item = customerReturn.getItems().get(0);
            return new ReturnSnapshot(customerReturn.getStatus(), customerReturn.getRmaId(),
                    item.getRequestedQuantity(), item.getAcceptedQuantity(), item.getDisposition());
        });
    }

    private record ReturnFixture(java.util.UUID requestKey, Long orderItemId) {
    }

    private record WorkflowSnapshot(
            OrderStatus orderStatus, DeliveryStatus deliveryStatus, int allocationAttemptCount,
            PaymentStatus paymentStatus, int orderAmount, int paidAmount,
            int pendingRefundAmount, int refundedAmount,
            PaymentAttemptStatus attemptStatus, int paymentAttemptCount,
            boolean gatewayTransactionPresent, int refundCount) {
    }

    private record RefundSnapshot(
            RefundStatus status, RefundSourceType sourceType, Long sourceId,
            int amount, int attemptCount, boolean requestKeyPresent) {
    }

    private record ReturnSnapshot(
            CustomerReturnStatus status, Long rmaId, int requestedQuantity,
            Integer acceptedQuantity, ReturnDisposition disposition) {
    }
}
