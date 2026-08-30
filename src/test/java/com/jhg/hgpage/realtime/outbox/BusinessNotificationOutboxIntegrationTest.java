package com.jhg.hgpage.realtime.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.contract.InventoryQueryPort;
import com.jhg.hgpage.contract.PaymentGateway;
import com.jhg.hgpage.contract.ReturnPort.ResultItem;
import com.jhg.hgpage.contract.ReturnPort.ReturnResult;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.PaymentAttempt;
import com.jhg.hgpage.oms.domain.RefundRequest;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.domain.enums.RefundSourceType;
import com.jhg.hgpage.oms.repository.OrderRepository;
import com.jhg.hgpage.oms.service.CustomerReturnService;
import com.jhg.hgpage.oms.service.OrderAllocationService;
import com.jhg.hgpage.oms.service.OrderCancellationService;
import com.jhg.hgpage.oms.service.OrderService;
import com.jhg.hgpage.oms.service.PaymentService;
import com.jhg.hgpage.oms.service.RefundService;
import com.jhg.hgpage.oms.service.ReturnSyncService;
import com.jhg.hgpage.wms.adapter.WmsInventoryQueryAdapter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "payments.sweep-delay=1h", "allocation.sweep-delay=1h",
        "refunds.sweep-delay=1h", "cancellations.sweep-delay=1h",
        "returns.sweep-delay=1h", "backorder.sweep-delay=1h",
        "spring.datasource.url=jdbc:h2:mem:business-notification-outbox;DB_CLOSE_DELAY=-1"
})
class BusinessNotificationOutboxIntegrationTest {

    @Autowired PaymentService paymentService;
    @Autowired OrderAllocationService allocationService;
    @Autowired OrderCancellationService cancellationService;
    @Autowired OrderService orderService;
    @Autowired CustomerReturnService customerReturnService;
    @Autowired ReturnSyncService returnSyncService;
    @Autowired RefundService refundService;
    @Autowired NotificationEventWriter eventWriter;
    @Autowired NotificationOutboxRepository outboxRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManager em;

    @MockitoBean WmsInventoryQueryAdapter inventoryQueryPort;
    @MockitoSpyBean ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        reset(objectMapper, inventoryQueryPort);
        outboxRepository.deleteAll();
    }

    @ParameterizedTest
    @MethodSource("paymentEvents")
    void payment_transitions_append_one_event(PaymentGateway.GatewayOutcome outcome,
                                               NotificationEventType eventType) throws Exception {
        PaymentFixture fixture = paymentFixture();
        paymentService.claimApproval(fixture.attemptId()).orElseThrow();

        paymentService.applyApprovalResult(fixture.attemptId(), new PaymentGateway.ApprovalResult(
                outcome, outcome == PaymentGateway.GatewayOutcome.SUCCESS ? "payment-tx" : null,
                "failure", "failure"));

        assertEvent(eventType, fixture.memberId(), "ORDER", fixture.orderId(),
                Map.of("orderId", fixture.orderId()));
    }

    private static Stream<Arguments> paymentEvents() {
        return Stream.of(
                Arguments.of(PaymentGateway.GatewayOutcome.SUCCESS, NotificationEventType.PAYMENT_APPROVED),
                Arguments.of(PaymentGateway.GatewayOutcome.DECLINED, NotificationEventType.PAYMENT_FAILED),
                Arguments.of(PaymentGateway.GatewayOutcome.PERMANENT_FAILURE,
                        NotificationEventType.PAYMENT_REVIEW_REQUIRED));
    }

    @Test
    void approval_during_cancellation_does_not_append_payment_approved() {
        PaymentFixture fixture = paymentFixture();
        paymentService.claimApproval(fixture.attemptId()).orElseThrow();
        transactionTemplate.executeWithoutResult(status -> orderRepository.findById(fixture.orderId()).orElseThrow()
                .requestCancellation(null, LocalDateTime.now()));

        paymentService.applyApprovalResult(fixture.attemptId(), new PaymentGateway.ApprovalResult(
                PaymentGateway.GatewayOutcome.SUCCESS, "payment-tx", null, null));

        assertThat(events(NotificationEventType.PAYMENT_APPROVED)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("allocationEvents")
    void allocation_transitions_append_one_event(boolean backorderRecovery, boolean reserved,
                                                  NotificationEventType eventType) throws Exception {
        OrderFixture fixture = allocationFixture(backorderRecovery);
        int attempt = allocationService.claim(fixture.orderId()).orElseThrow().attemptNumber();

        allocationService.complete(fixture.orderId(), attempt, reserved);

        assertEvent(eventType, fixture.memberId(), "ORDER", fixture.orderId(),
                Map.of("orderId", fixture.orderId()));
    }

    private static Stream<Arguments> allocationEvents() {
        return Stream.of(
                Arguments.of(false, true, NotificationEventType.STOCK_ALLOCATED),
                Arguments.of(true, true, NotificationEventType.STOCK_ALLOCATED),
                Arguments.of(false, false, NotificationEventType.ORDER_BACKORDERED));
    }

    @Test
    void completed_cancellation_appends_one_event() throws Exception {
        OrderFixture fixture = orderFixture();
        transactionTemplate.executeWithoutResult(status ->
                orderRepository.findById(fixture.orderId()).orElseThrow().markBackordered());

        cancellationService.request(fixture.orderId(), fixture.memberId());
        cancellationService.request(fixture.orderId(), fixture.memberId());

        assertEvent(NotificationEventType.ORDER_CANCELLED, fixture.memberId(), "ORDER", fixture.orderId(),
                Map.of("orderId", fixture.orderId()));
    }

    @Test
    void ready_to_delivered_sync_appends_both_events_once() throws Exception {
        OrderFixture fixture = orderFixture();
        Instant issuedAt = Instant.parse("2026-08-30T01:00:00Z");
        Instant deliveredAt = Instant.parse("2026-08-30T02:00:00Z");
        when(inventoryQueryPort.shipmentByOrderId(fixture.orderId())).thenReturn(Optional.of(
                new InventoryQueryPort.ShipmentInfo(fixture.orderId(), "CJ", "CJ", "TRACK", issuedAt,
                        deliveredAt)));

        orderService.syncShipment(fixture.orderId());
        orderService.syncShipment(fixture.orderId());

        assertThat(eventTypes()).containsExactly(
                NotificationEventType.SHIPMENT_STARTED, NotificationEventType.DELIVERY_COMPLETED);
        assertEvent(NotificationEventType.SHIPMENT_STARTED, fixture.memberId(), "ORDER", fixture.orderId(),
                Map.of("orderId", fixture.orderId()));
        assertEvent(NotificationEventType.DELIVERY_COMPLETED, fixture.memberId(), "ORDER", fixture.orderId(),
                Map.of("orderId", fixture.orderId()));
    }

    @Test
    void rejected_return_appends_one_event() throws Exception {
        ReturnFixture fixture = returnFixture(false);

        customerReturnService.rejectReturn(fixture.returnId(), "admin@example.com", "not eligible");

        assertReturnEvent(NotificationEventType.RETURN_REJECTED, fixture);
    }

    @ParameterizedTest
    @MethodSource("returnEvents")
    void return_callbacks_append_only_the_new_target_event(String target,
                                                           NotificationEventType eventType) throws Exception {
        ReturnFixture fixture = returnFixture(true);
        ReturnResult result = returnResult(fixture, target);

        returnSyncService.apply(result);
        returnSyncService.apply(result);

        assertReturnEvent(eventType, fixture);
        assertThat(outboxRepository.count()).isOne();
    }

    private static Stream<Arguments> returnEvents() {
        return Stream.of(
                Arguments.of("REQUESTED", NotificationEventType.RETURN_REQUESTED),
                Arguments.of("RECEIVED", NotificationEventType.RETURN_RECEIVED),
                Arguments.of("COMPLETED", NotificationEventType.RETURN_COMPLETED),
                Arguments.of("CANCELLED", NotificationEventType.RETURN_CANCELLED));
    }

    @ParameterizedTest
    @MethodSource("refundEvents")
    void refund_transitions_append_one_event(PaymentGateway.GatewayOutcome outcome,
                                              NotificationEventType eventType) throws Exception {
        RefundFixture fixture = refundFixture();
        int attempt = refundService.claim(fixture.refundId()).orElseThrow().attemptNumber();
        PaymentGateway.RefundResult result = new PaymentGateway.RefundResult(outcome,
                outcome == PaymentGateway.GatewayOutcome.SUCCESS ? "refund-tx" : null,
                "failure", "failure");

        refundService.applyResult(fixture.refundId(), attempt, result);
        refundService.applyResult(fixture.refundId(), attempt, result);

        assertEvent(eventType, fixture.memberId(), "REFUND", fixture.refundId(), Map.of(
                "orderId", fixture.orderId(), "refundId", fixture.refundId(), "amount", fixture.amount()));
        assertThat(outboxRepository.count()).isOne();
    }

    private static Stream<Arguments> refundEvents() {
        return Stream.of(
                Arguments.of(PaymentGateway.GatewayOutcome.SUCCESS, NotificationEventType.REFUND_COMPLETED),
                Arguments.of(PaymentGateway.GatewayOutcome.PERMANENT_FAILURE,
                        NotificationEventType.REFUND_REVIEW_REQUIRED));
    }

    @Test
    void exception_after_append_rolls_back_business_state_and_outbox() {
        OrderFixture fixture = orderFixture();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            Order order = orderRepository.findById(fixture.orderId()).orElseThrow();
            order.ship();
            eventWriter.append(NotificationEventType.SHIPMENT_STARTED, fixture.memberId(),
                    "ORDER", fixture.orderId().toString(), Map.of("orderId", fixture.orderId()));
            throw new IllegalStateException("after append");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(deliveryStatus(fixture.orderId())).isEqualTo(DeliveryStatus.READY);
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void serialization_failure_rolls_back_business_state() throws Exception {
        OrderFixture fixture = orderFixture();
        when(inventoryQueryPort.shipmentByOrderId(fixture.orderId())).thenReturn(Optional.of(
                new InventoryQueryPort.ShipmentInfo(fixture.orderId(), "CJ", "CJ", "TRACK",
                        Instant.parse("2026-08-30T01:00:00Z"), null)));
        doThrow(new JsonProcessingException("serialization failure") { })
                .when(objectMapper).writeValueAsString(any(NotificationEventPayload.class));

        assertThatThrownBy(() -> orderService.syncShipment(fixture.orderId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serialize");

        reset(objectMapper);
        assertThat(deliveryStatus(fixture.orderId())).isEqualTo(DeliveryStatus.READY);
        assertThat(outboxRepository.count()).isZero();
    }

    private PaymentFixture paymentFixture() {
        return transactionTemplate.execute(status -> {
            OrderFixture order = persistOrder();
            Order entity = em.find(Order.class, order.orderId());
            entity.markPaymentPending();
            Payment payment = Payment.create(entity, entity.getTotalPrice());
            em.persist(payment);
            PaymentAttempt attempt = PaymentAttempt.create(payment, UUID.randomUUID());
            em.persist(attempt);
            em.flush();
            return new PaymentFixture(order.memberId(), order.orderId(), attempt.getId());
        });
    }

    private OrderFixture allocationFixture(boolean backorderRecovery) {
        return transactionTemplate.execute(status -> {
            OrderFixture fixture = persistOrder();
            Order order = em.find(Order.class, fixture.orderId());
            if (backorderRecovery) {
                order.markBackordered();
                order.setStatus(OrderStatus.ALLOCATION_PENDING);
                order.setNextAllocationAttemptAt(LocalDateTime.now());
            } else {
                order.markPaymentPending();
                order.markAllocationPending();
            }
            return fixture;
        });
    }

    private OrderFixture orderFixture() {
        return transactionTemplate.execute(status -> persistOrder());
    }

    private OrderFixture persistOrder() {
        Product product = new Product();
        product.setName("notification product " + UUID.randomUUID());
        product.setPrice(10_000);
        em.persist(product);
        Member member = Member.createUser("notification member", "010-0000-0000",
                new Address("Seoul", "Gwanak", "500"));
        em.persist(member);
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("Seoul", "Gwanak", "500"));
        Order order = Order.createOrder(member, delivery,
                OrderItem.createOrderItem(product, product.getPrice(), 1));
        em.persist(order);
        em.flush();
        return new OrderFixture(member.getId(), order.getId());
    }

    private ReturnFixture returnFixture(boolean approved) {
        return transactionTemplate.execute(status -> {
            OrderFixture fixture = persistOrder();
            Order order = em.find(Order.class, fixture.orderId());
            order.ship();
            order.deliver();
            Payment payment = Payment.create(order, order.getTotalPrice());
            payment.markPaid(LocalDateTime.now());
            em.persist(payment);
            OrderItem item = order.getOrderItems().get(0);
            CustomerReturn customerReturn = CustomerReturn.create(order, UUID.randomUUID(), "damaged",
                    List.of(new CustomerReturn.RequestItem(item, 1)));
            if (approved) customerReturn.approve("admin@example.com");
            em.persist(customerReturn);
            em.flush();
            return new ReturnFixture(fixture.memberId(), fixture.orderId(), customerReturn.getId(),
                    customerReturn.getRequestKey(), item.getId(), item.getProduct().getId());
        });
    }

    private RefundFixture refundFixture() {
        return transactionTemplate.execute(status -> {
            OrderFixture fixture = persistOrder();
            Order order = em.find(Order.class, fixture.orderId());
            Payment payment = Payment.create(order, order.getTotalPrice());
            payment.markPaid(LocalDateTime.now());
            payment.reserveRefund(10_000);
            em.persist(payment);
            RefundRequest refund = RefundRequest.create(payment, UUID.randomUUID(),
                    RefundSourceType.ORDER_CANCEL, order.getId(), 10_000);
            em.persist(refund);
            em.flush();
            return new RefundFixture(fixture.memberId(), fixture.orderId(), refund.getId(), refund.getAmount());
        });
    }

    private ReturnResult returnResult(ReturnFixture fixture, String target) {
        int accepted = 0;
        String disposition = target.equals("COMPLETED") ? "REJECTED" : null;
        return new ReturnResult(9000L + fixture.returnId(), fixture.requestKey(), fixture.orderId(), target,
                List.of(new ResultItem(fixture.orderItemId(), fixture.productId(), 1, accepted, disposition)));
    }

    private void assertReturnEvent(NotificationEventType type, ReturnFixture fixture) throws Exception {
        assertEvent(type, fixture.memberId(), "RETURN", fixture.returnId(), Map.of(
                "orderId", fixture.orderId(), "returnId", fixture.returnId()));
    }

    private void assertEvent(NotificationEventType type, Long recipientId, String aggregateType,
                             Long aggregateId, Map<String, Object> data) throws Exception {
        NotificationOutbox outbox = events(type).get(0);
        JsonNode payload = objectMapper.readTree(outbox.getPayload());
        assertThat(outbox.getRecipientId()).isEqualTo(recipientId);
        assertThat(outbox.getAggregateType()).isEqualTo(aggregateType);
        assertThat(outbox.getAggregateId()).isEqualTo(aggregateId.toString());
        data.forEach((key, value) -> assertThat(payload.at("/data/" + key).asLong())
                .isEqualTo(((Number) value).longValue()));
        assertThat(events(type)).hasSize(1);
    }

    private List<NotificationOutbox> events(NotificationEventType type) {
        return outboxRepository.findAll().stream().filter(row -> row.getEventType() == type).toList();
    }

    private List<NotificationEventType> eventTypes() {
        return outboxRepository.findAll().stream()
                .sorted(Comparator.comparing(NotificationOutbox::getCreatedAt))
                .map(NotificationOutbox::getEventType)
                .toList();
    }

    private DeliveryStatus deliveryStatus(Long orderId) {
        return transactionTemplate.execute(status -> orderRepository.findById(orderId).orElseThrow()
                .getDelivery().getStatus());
    }

    private record OrderFixture(Long memberId, Long orderId) { }
    private record PaymentFixture(Long memberId, Long orderId, Long attemptId) { }
    private record ReturnFixture(Long memberId, Long orderId, Long returnId, UUID requestKey,
                                 Long orderItemId, Long productId) { }
    private record RefundFixture(Long memberId, Long orderId, Long refundId, int amount) { }
}
