package com.jhg.hgpage.service;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.contract.ReturnPort.ResultItem;
import com.jhg.hgpage.contract.ReturnPort.ReturnResult;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.CustomerReturnItem;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.RefundRequest;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.domain.enums.RefundSourceType;
import com.jhg.hgpage.oms.domain.enums.ReturnDisposition;
import com.jhg.hgpage.oms.repository.CustomerReturnRepository;
import com.jhg.hgpage.oms.repository.PaymentRepository;
import com.jhg.hgpage.oms.repository.RefundRequestRepository;
import com.jhg.hgpage.oms.service.RefundService;
import com.jhg.hgpage.oms.service.RetrySchedule;
import com.jhg.hgpage.oms.service.ReturnSyncService;
import com.jhg.hgpage.realtime.outbox.NotificationEventWriter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ReturnSyncService.class, RefundService.class, RetrySchedule.class})
class ReturnSyncServiceTest {

    @Autowired ReturnSyncService returnSyncService;
    @Autowired CustomerReturnRepository customerReturnRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired RefundRequestRepository refundRequestRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManager em;
    @MockitoBean NotificationEventWriter eventWriter;

    @ParameterizedTest
    @MethodSource("inProgressStatuses")
    void 진행중_결과는_RMA를_바인딩하고_품목결과를_적용하지_않는다(String remoteStatus,
                                                            CustomerReturnStatus expectedStatus) {
        Fixture fixture = pendingReturn();

        returnSyncService.apply(result(fixture, remoteStatus));

        CustomerReturn saved = saved(fixture.returnId());
        assertThat(saved.getRmaId()).isEqualTo(fixture.rmaId());
        assertThat(saved.getStatus()).isEqualTo(expectedStatus);
        assertThat(saved.getItems()).extracting(CustomerReturnItem::getAcceptedQuantity)
                .containsOnlyNulls();
        assertThat(saved.getItems()).extracting(CustomerReturnItem::getDisposition)
                .containsOnlyNulls();
    }

    @Test
    void 완료_결과는_정확한_품목별_승인수량과_처분을_적용한다() {
        Fixture fixture = pendingReturn();

        returnSyncService.apply(result(fixture, "COMPLETED"));

        CustomerReturn saved = saved(fixture.returnId());
        assertThat(saved.getRmaId()).isEqualTo(fixture.rmaId());
        assertThat(saved.getStatus()).isEqualTo(CustomerReturnStatus.COMPLETED);
        assertThat(saved.getItems()).extracting(CustomerReturnItem::getAcceptedQuantity)
                .containsExactly(1, 0);
        assertThat(saved.getItems()).extracting(CustomerReturnItem::getDisposition)
                .containsExactly(ReturnDisposition.RESTOCKED, ReturnDisposition.REJECTED);
    }

    @Test
    void 완료_반품은_RESTOCKED와_DISPOSED의_부분승인수량을_주문당시가격으로_환불한다() {
        Fixture fixture = pendingReturn();
        transactionTemplate.executeWithoutResult(status -> {
            em.find(Product.class, fixture.firstProductId()).setPrice(99_000);
            em.find(Product.class, fixture.secondProductId()).setPrice(88_000);
        });
        ReturnResult completed = completed(fixture, 1, "RESTOCKED", 1, "DISPOSED");

        returnSyncService.apply(completed);

        RefundRequest refund = returnRefund(fixture.returnId());
        assertThat(refund.getAmount()).isEqualTo(30_000);
        assertThat(refund.getSourceType()).isEqualTo(RefundSourceType.RETURN);
        assertThat(payment(fixture.orderId()).getPendingRefundAmount()).isEqualTo(30_000);
    }

    @Test
    void 완료_반품의_승인수량이_모두_0이면_환불요청을_만들지_않는다() {
        Fixture fixture = pendingReturn();

        returnSyncService.apply(completed(fixture, 0, "REJECTED", 0, "REJECTED"));

        assertThat(refundRequestRepository.findBySourceTypeAndSourceId(
                RefundSourceType.RETURN, fixture.returnId())).isEmpty();
        assertThat(payment(fixture.orderId()).getPendingRefundAmount()).isZero();
    }

    @Test
    void 취소_결과는_승인수량_0과_처분_null일_때만_적용한다() {
        Fixture fixture = pendingReturn();

        returnSyncService.apply(result(fixture, "CANCELLED"));

        CustomerReturn saved = saved(fixture.returnId());
        assertThat(saved.getRmaId()).isEqualTo(fixture.rmaId());
        assertThat(saved.getStatus()).isEqualTo(CustomerReturnStatus.CANCELLED);
        assertThat(saved.getItems()).extracting(CustomerReturnItem::getAcceptedQuantity)
                .containsOnly(0);
        assertThat(saved.getItems()).extracting(CustomerReturnItem::getDisposition)
                .containsOnlyNulls();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("identityAndItemMismatches")
    void 식별자와_정확한_품목계약이_다르면_아무것도_적용하지_않는다(
            String ignored, UnaryOperator<ReturnResult> corrupt) {
        Fixture fixture = pendingReturn();

        assertMismatch(fixture, corrupt.apply(result(fixture, "COMPLETED")));
    }

    @Test
    void 이미_바인딩된_RMA와_다른_결과는_거절한다() {
        Fixture fixture = pendingReturn();
        transactionTemplate.executeWithoutResult(status ->
                saved(fixture.returnId()).markRequested(fixture.rmaId()));
        ReturnResult result = result(fixture, "COMPLETED");
        ReturnResult mismatched = copy(result, result.requestKey(), result.rmaId() + 1,
                result.orderId(), result.status(), result.items());

        assertThatThrownBy(() -> returnSyncService.apply(mismatched))
                .isInstanceOf(ReturnSyncService.ReturnContractMismatchException.class);

        CustomerReturn saved = saved(fixture.returnId());
        assertThat(saved.getRmaId()).isEqualTo(fixture.rmaId());
        assertThat(saved.getStatus()).isEqualTo(CustomerReturnStatus.REQUESTED);
    }

    @Test
    void 과거_반품과_RMA번호가_같아도_requestKey로_현재_반품을_완료한다() {
        Fixture historical = pendingReturn();
        Fixture target = pendingReturn();
        returnSyncService.apply(result(historical, "COMPLETED"));
        ReturnResult targetResult = result(target, "COMPLETED");
        ReturnResult reused = copy(targetResult, targetResult.requestKey(), historical.rmaId(),
                targetResult.orderId(), targetResult.status(), targetResult.items());

        returnSyncService.apply(reused);

        assertThat(saved(target.returnId()).getRmaId()).isEqualTo(historical.rmaId());
        assertThat(saved(target.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.COMPLETED);
        assertThat(returnRefund(target.returnId()).getAmount()).isEqualTo(10_000);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("statusAndResultMismatches")
    void 상태별_승인수량과_처분계약이_다르면_아무것도_적용하지_않는다(
            String ignored, UnaryOperator<ReturnResult> corrupt) {
        Fixture fixture = pendingReturn();

        assertMismatch(fixture, corrupt.apply(result(fixture, "COMPLETED")));
    }

    @Test
    void 같은_완료_결과는_멱등_no_op이다() {
        Fixture fixture = pendingReturn();
        ReturnResult result = result(fixture, "COMPLETED");
        returnSyncService.apply(result);
        LocalDateTime updatedAt = saved(fixture.returnId()).getUpdatedAt();

        returnSyncService.apply(result);

        CustomerReturn saved = saved(fixture.returnId());
        assertThat(saved.getStatus()).isEqualTo(CustomerReturnStatus.COMPLETED);
        assertThat(saved.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(returnRefund(fixture.returnId()).getAmount()).isEqualTo(10_000);
        assertThat(payment(fixture.orderId()).getPendingRefundAmount()).isEqualTo(10_000);
    }

    @Test
    void 완료_후_다른_검수결과는_거절하고_기존_환불만_유지한다() {
        Fixture fixture = pendingReturn();
        returnSyncService.apply(result(fixture, "COMPLETED"));

        assertThatThrownBy(() -> returnSyncService.apply(completed(
                fixture, 2, "RESTOCKED", 0, "REJECTED")))
                .isInstanceOf(ReturnSyncService.ReturnContractMismatchException.class);

        CustomerReturn saved = saved(fixture.returnId());
        assertThat(saved.getItems()).extracting(CustomerReturnItem::getAcceptedQuantity)
                .containsExactly(1, 0);
        assertThat(returnRefund(fixture.returnId()).getAmount()).isEqualTo(10_000);
        assertThat(payment(fixture.orderId()).getPendingRefundAmount()).isEqualTo(10_000);
    }

    @Test
    void 같은_주문의_별도_반품은_승인수량만큼_누적_환불한다() {
        Fixture first = pendingReturn();
        returnSyncService.apply(result(first, "COMPLETED"));
        SingleReturnFixture second = additionalReturn(first);

        returnSyncService.apply(result(second, 1, "DISPOSED"));

        assertThat(returnRefund(first.returnId()).getAmount()).isEqualTo(10_000);
        assertThat(returnRefund(second.returnId()).getAmount()).isEqualTo(10_000);
        assertThat(payment(first.orderId()).getPendingRefundAmount()).isEqualTo(20_000);
    }

    @Test
    void 과환불이면_완료상태와_검수결과와_환불요청을_함께_롤백한다() {
        Fixture fixture = pendingReturn();
        transactionTemplate.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findByOrderIdForUpdate(fixture.orderId()).orElseThrow();
            payment.reserveRefund(payment.getPaidAmount());
            refundRequestRepository.save(RefundRequest.create(payment, UUID.randomUUID(),
                    RefundSourceType.ORDER_CANCEL, fixture.orderId(), payment.getPaidAmount()));
        });

        assertThatThrownBy(() -> returnSyncService.apply(result(fixture, "COMPLETED")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("환불 가능 금액");

        CustomerReturn saved = saved(fixture.returnId());
        assertThat(saved.getStatus()).isEqualTo(CustomerReturnStatus.PENDING_SUBMISSION);
        assertThat(saved.getRmaId()).isNull();
        assertThat(saved.getItems()).extracting(CustomerReturnItem::getAcceptedQuantity)
                .containsOnlyNulls();
        assertThat(refundRequestRepository.findBySourceTypeAndSourceId(
                RefundSourceType.RETURN, fixture.returnId())).isEmpty();
        assertThat(refundRequestRepository.findBySourceTypeAndSourceId(
                RefundSourceType.ORDER_CANCEL, fixture.orderId())).isPresent();
        Payment payment = payment(fixture.orderId());
        assertThat(payment.getPendingRefundAmount()).isEqualTo(payment.getPaidAmount());
    }

    @Test
    void 승인수량이_128_이상이어도_같은_완료_결과는_멱등_no_op이다() {
        Fixture fixture = pendingReturn(200, 200);
        ReturnResult result = new ReturnResult(fixture.rmaId(), fixture.requestKey(), fixture.orderId(),
                "COMPLETED", List.of(
                item(fixture.firstOrderItemId(), fixture.firstProductId(), 200, 128, "RESTOCKED"),
                item(fixture.secondOrderItemId(), fixture.secondProductId(), 1, 0, "REJECTED")));
        returnSyncService.apply(result);
        LocalDateTime updatedAt = saved(fixture.returnId()).getUpdatedAt();

        returnSyncService.apply(result);

        CustomerReturn saved = saved(fixture.returnId());
        assertThat(saved.getStatus()).isEqualTo(CustomerReturnStatus.COMPLETED);
        assertThat(saved.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void 이전_진행상태는_회귀시키지_않는다() {
        Fixture fixture = pendingReturn();
        returnSyncService.apply(result(fixture, "RECEIVED"));

        returnSyncService.apply(result(fixture, "REQUESTED"));

        assertThat(saved(fixture.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.RECEIVED);
    }

    @Test
    void OMS_승인전_콜백은_반품상태를_변경할수없다() {
        Fixture fixture = pendingApprovalReturn();

        assertThatThrownBy(() -> returnSyncService.apply(result(fixture, "REQUESTED")))
                .isInstanceOf(ReturnSyncService.ReturnContractMismatchException.class);

        assertThat(saved(fixture.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.PENDING_APPROVAL);
    }

    @Test
    void 반려된_반품의_콜백은_반품상태를_변경할수없다() {
        Fixture fixture = pendingApprovalReturn();
        transactionTemplate.executeWithoutResult(status ->
                saved(fixture.returnId()).reject("admin@example.com", "반품 불가"));

        assertThatThrownBy(() -> returnSyncService.apply(result(fixture, "REQUESTED")))
                .isInstanceOf(ReturnSyncService.ReturnContractMismatchException.class);

        assertThat(saved(fixture.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.REJECTED);
    }

    @ParameterizedTest
    @MethodSource("conflictingTerminalTransitions")
    void 서로_다른_종료상태로는_변경하지_않는다(String initial, String conflicting) {
        Fixture fixture = pendingReturn();
        returnSyncService.apply(result(fixture, initial));

        assertThatThrownBy(() -> returnSyncService.apply(result(fixture, conflicting)))
                .isInstanceOf(ReturnSyncService.ReturnContractMismatchException.class);

        assertThat(saved(fixture.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.valueOf(initial));
    }

    private void assertMismatch(Fixture fixture, ReturnResult result) {
        assertThatThrownBy(() -> returnSyncService.apply(result))
                .isInstanceOf(ReturnSyncService.ReturnContractMismatchException.class);
        CustomerReturn saved = saved(fixture.returnId());
        assertThat(saved.getStatus()).isEqualTo(CustomerReturnStatus.PENDING_SUBMISSION);
        assertThat(saved.getRmaId()).isNull();
        assertThat(saved.getItems()).extracting(CustomerReturnItem::getAcceptedQuantity)
                .containsOnlyNulls();
    }

    private static Stream<Arguments> inProgressStatuses() {
        return Stream.of(
                Arguments.of("REQUESTED", CustomerReturnStatus.REQUESTED),
                Arguments.of("RECEIVED", CustomerReturnStatus.RECEIVED));
    }

    private static Stream<Arguments> conflictingTerminalTransitions() {
        return Stream.of(Arguments.of("COMPLETED", "CANCELLED"), Arguments.of("CANCELLED", "COMPLETED"));
    }

    private static Stream<Arguments> identityAndItemMismatches() {
        return Stream.of(
                Arguments.of("requestKey null", change(result -> copy(result, null, result.rmaId(),
                        result.orderId(), result.status(), result.items()))),
                Arguments.of("unknown requestKey", change(result -> copy(result, UUID.randomUUID(), result.rmaId(),
                        result.orderId(), result.status(), result.items()))),
                Arguments.of("rmaId null", change(result -> copy(result, result.requestKey(), null,
                        result.orderId(), result.status(), result.items()))),
                Arguments.of("orderId mismatch", change(result -> copy(result, result.requestKey(), result.rmaId(),
                        result.orderId() + 1, result.status(), result.items()))),
                Arguments.of("missing item", change(result -> withItems(result, List.of(result.items().get(0))))),
                Arguments.of("duplicate item", change(result -> withItems(result,
                        List.of(result.items().get(0), result.items().get(0))))),
                Arguments.of("orderItemId mismatch", change(result -> withFirstItem(result,
                        item -> item(item.orderItemId() + 999, item.productId(), item.requestedQuantity(),
                                item.acceptedQuantity(), item.disposition())))),
                Arguments.of("productId mismatch", change(result -> withFirstItem(result,
                        item -> item(item.orderItemId(), item.productId() + 1, item.requestedQuantity(),
                                item.acceptedQuantity(), item.disposition())))),
                Arguments.of("requestedQuantity mismatch", change(result -> withFirstItem(result,
                        item -> item(item.orderItemId(), item.productId(), item.requestedQuantity() + 1,
                                item.acceptedQuantity(), item.disposition())))));
    }

    private static Stream<Arguments> statusAndResultMismatches() {
        return Stream.of(
                Arguments.of("unknown status", change(result -> copy(result, result.requestKey(), result.rmaId(),
                        result.orderId(), "RETURNED", result.items()))),
                Arguments.of("completed negative accepted", completedItem(-1, "REJECTED")),
                Arguments.of("completed over accepted", completedItem(3, "RESTOCKED")),
                Arguments.of("completed zero without rejected", completedItem(0, null)),
                Arguments.of("completed zero wrong disposition", completedItem(0, "RESTOCKED")),
                Arguments.of("completed positive without disposition", completedItem(1, null)),
                Arguments.of("completed positive rejected", completedItem(1, "REJECTED")),
                Arguments.of("completed unknown disposition", completedItem(1, "DONATE")),
                Arguments.of("cancelled accepted", statusItem("CANCELLED", 1, null)),
                Arguments.of("cancelled disposition", statusItem("CANCELLED", 0, "REJECTED")),
                Arguments.of("requested premature result", statusItem("REQUESTED", 1, "RESTOCKED")),
                Arguments.of("received premature disposition", statusItem("RECEIVED", 0, "REJECTED")));
    }

    private static UnaryOperator<ReturnResult> completedItem(int accepted, String disposition) {
        return statusItem("COMPLETED", accepted, disposition);
    }

    private static UnaryOperator<ReturnResult> statusItem(String status, int accepted, String disposition) {
        return change(result -> withFirstItem(copy(result, result.requestKey(), result.rmaId(), result.orderId(),
                        status, status.equals("COMPLETED") ? result.items() : result.items().stream()
                                .map(item -> item(item.orderItemId(), item.productId(), item.requestedQuantity(), 0, null))
                                .toList()),
                item -> item(item.orderItemId(), item.productId(), item.requestedQuantity(), accepted, disposition)));
    }

    private static UnaryOperator<ReturnResult> change(UnaryOperator<ReturnResult> change) {
        return change;
    }

    private static ReturnResult withFirstItem(ReturnResult result, UnaryOperator<ResultItem> change) {
        return withItems(result, List.of(change.apply(result.items().get(0)), result.items().get(1)));
    }

    private static ReturnResult withItems(ReturnResult result, List<ResultItem> items) {
        return copy(result, result.requestKey(), result.rmaId(), result.orderId(), result.status(), items);
    }

    private static ReturnResult copy(ReturnResult result, UUID requestKey, Long rmaId, Long orderId,
                                     String status, List<ResultItem> items) {
        return new ReturnResult(rmaId, requestKey, orderId, status, items);
    }

    private static ResultItem item(Long orderItemId, Long productId, int requested, int accepted,
                                   String disposition) {
        return new ResultItem(orderItemId, productId, requested, accepted, disposition);
    }

    private Fixture pendingReturn() {
        return pendingReturn(3, 2);
    }

    private Fixture pendingReturn(int firstOrderQuantity, int firstRequestedQuantity) {
        return pendingReturn(firstOrderQuantity, firstRequestedQuantity, true);
    }

    private Fixture pendingApprovalReturn() {
        return pendingReturn(3, 2, false);
    }

    private Fixture pendingReturn(int firstOrderQuantity, int firstRequestedQuantity, boolean approved) {
        return transactionTemplate.execute(status -> {
            Product firstProduct = product("첫 상품");
            Product secondProduct = product("둘째 상품");
            Member member = Member.createUser("테스터", "010-0000-0000",
                    new Address("서울", "관악구", "500"));
            em.persist(member);
            Delivery delivery = new Delivery();
            delivery.setAddress(new Address("서울", "관악구", "500"));
            OrderItem firstItem = OrderItem.createOrderItem(firstProduct, firstProduct.getPrice(), firstOrderQuantity);
            OrderItem secondItem = OrderItem.createOrderItem(secondProduct, secondProduct.getPrice(), 2);
            Order order = Order.createOrder(member, delivery, firstItem, secondItem);
            order.ship();
            order.deliver();
            em.persist(order);
            Payment payment = Payment.create(order, order.getTotalPrice());
            payment.markPaid(LocalDateTime.now());
            em.persist(payment);
            em.flush();
            UUID requestKey = UUID.randomUUID();
            Long rmaId = 1000L + order.getId();
            CustomerReturn customerReturn = CustomerReturn.create(order, requestKey, "불량", List.of(
                    new CustomerReturn.RequestItem(firstItem, firstRequestedQuantity),
                    new CustomerReturn.RequestItem(secondItem, 1)));
            if (approved) customerReturn.approve("admin@example.com");
            em.persist(customerReturn);
            em.flush();
            return new Fixture(customerReturn.getId(), requestKey, rmaId, order.getId(),
                    firstItem.getId(), firstProduct.getId(), secondItem.getId(), secondProduct.getId());
        });
    }

    private Product product(String name) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(name.equals("첫 상품") ? 10_000 : 20_000);
        em.persist(product);
        return product;
    }

    private SingleReturnFixture additionalReturn(Fixture fixture) {
        return transactionTemplate.execute(status -> {
            Order order = em.find(Order.class, fixture.orderId());
            OrderItem item = em.find(OrderItem.class, fixture.firstOrderItemId());
            CustomerReturn customerReturn = CustomerReturn.create(order, UUID.randomUUID(), "추가 불량",
                    List.of(new CustomerReturn.RequestItem(item, 1)));
            customerReturn.approve("admin@example.com");
            em.persist(customerReturn);
            em.flush();
            return new SingleReturnFixture(customerReturn.getId(), customerReturn.getRequestKey(),
                    2000L + customerReturn.getId(), order.getId(), item.getId(), item.getProduct().getId());
        });
    }

    private CustomerReturn saved(Long returnId) {
        return customerReturnRepository.findDetailedById(returnId).orElseThrow();
    }

    private Payment payment(Long orderId) {
        return paymentRepository.findByOrderId(orderId).orElseThrow();
    }

    private RefundRequest returnRefund(Long returnId) {
        return refundRequestRepository.findBySourceTypeAndSourceId(RefundSourceType.RETURN, returnId)
                .orElseThrow();
    }

    private ReturnResult result(Fixture fixture, String status) {
        int firstAccepted = status.equals("COMPLETED") ? 1 : 0;
        int secondAccepted = 0;
        String firstDisposition = status.equals("COMPLETED") ? "RESTOCKED" : null;
        String secondDisposition = status.equals("COMPLETED") ? "REJECTED" : null;
        return new ReturnResult(fixture.rmaId(), fixture.requestKey(), fixture.orderId(), status, List.of(
                item(fixture.firstOrderItemId(), fixture.firstProductId(), 2,
                        firstAccepted, firstDisposition),
                item(fixture.secondOrderItemId(), fixture.secondProductId(), 1,
                        secondAccepted, secondDisposition)));
    }

    private ReturnResult completed(Fixture fixture, int firstAccepted, String firstDisposition,
                                   int secondAccepted, String secondDisposition) {
        return new ReturnResult(fixture.rmaId(), fixture.requestKey(), fixture.orderId(), "COMPLETED", List.of(
                item(fixture.firstOrderItemId(), fixture.firstProductId(), 2,
                        firstAccepted, firstDisposition),
                item(fixture.secondOrderItemId(), fixture.secondProductId(), 1,
                        secondAccepted, secondDisposition)));
    }

    private ReturnResult result(SingleReturnFixture fixture, int accepted, String disposition) {
        return new ReturnResult(fixture.rmaId(), fixture.requestKey(), fixture.orderId(), "COMPLETED", List.of(
                item(fixture.orderItemId(), fixture.productId(), 1, accepted, disposition)));
    }

    private record Fixture(Long returnId, UUID requestKey, Long rmaId, Long orderId,
                           Long firstOrderItemId, Long firstProductId,
                           Long secondOrderItemId, Long secondProductId) {}

    private record SingleReturnFixture(Long returnId, UUID requestKey, Long rmaId, Long orderId,
                                       Long orderItemId, Long productId) {}
}
