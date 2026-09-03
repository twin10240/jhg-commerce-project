package com.jhg.hgpage.service;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.contract.ReturnPort;
import com.jhg.hgpage.contract.ReturnPort.CreateItem;
import com.jhg.hgpage.contract.ReturnPort.CreateRequest;
import com.jhg.hgpage.contract.ReturnPort.ResultItem;
import com.jhg.hgpage.contract.ReturnPort.ReturnAuthenticationFailure;
import com.jhg.hgpage.contract.ReturnPort.ReturnResult;
import com.jhg.hgpage.contract.ReturnPort.TransientReturnFailure;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.repository.CustomerReturnRepository;
import com.jhg.hgpage.oms.service.CustomerReturnService;
import com.jhg.hgpage.oms.service.RefundService;
import com.jhg.hgpage.oms.service.RetrySchedule;
import com.jhg.hgpage.oms.service.ReturnSubmissionService;
import com.jhg.hgpage.realtime.outbox.NotificationEventWriter;
import com.jhg.hgpage.oms.service.ReturnSyncService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({CustomerReturnService.class, ReturnSyncService.class, RefundService.class,
        RetrySchedule.class, ReturnSubmissionService.class})
class ReturnSubmissionServiceTest {

    @Autowired ReturnSubmissionService returnSubmissionService;
    @Autowired CustomerReturnService customerReturnService;
    @Autowired CustomerReturnRepository customerReturnRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManager em;
    @MockitoBean NotificationEventWriter eventWriter;
    @MockitoBean ReturnPort returnPort;

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPostResults")
    void POST_성공결과도_전체_계약을_검증한다(String ignored,
                                      java.util.function.UnaryOperator<ReturnResult> corrupt) {
        Fixture fixture = pendingReturn();
        when(returnPort.create(any())).thenReturn(corrupt.apply(result(fixture)));

        assertThatThrownBy(() -> returnSubmissionService.submit(fixture.returnId()))
                .isInstanceOf(ReturnSyncService.ReturnContractMismatchException.class);

        assertThat(saved(fixture.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.PENDING_SUBMISSION);
    }

    @Test
    void POST가_완료결과를_반환하면_즉시_품목결과까지_적용한다() {
        Fixture fixture = pendingReturn();
        when(returnPort.create(any())).thenReturn(completedResult(fixture));

        returnSubmissionService.submit(fixture.returnId());

        CustomerReturn saved = saved(fixture.returnId());
        assertThat(saved.getStatus()).isEqualTo(CustomerReturnStatus.COMPLETED);
        assertThat(saved.getItems().get(0).getAcceptedQuantity()).isEqualTo(1);
    }

    @Test
    void POST가_다른_유효한_반품결과를_반환해도_현재_접수와_상관관계를_검증한다() {
        Fixture submitted = pendingReturn();
        Fixture other = pendingReturn();
        when(returnPort.create(any())).thenReturn(result(other));

        assertThatThrownBy(() -> returnSubmissionService.submit(submitted.returnId()))
                .isInstanceOf(ReturnSyncService.ReturnContractMismatchException.class);

        assertThat(saved(submitted.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.PENDING_SUBMISSION);
        assertThat(saved(other.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.PENDING_SUBMISSION);
    }

    @Test
    void WMS_접수는_DB_트랜잭션_밖에서_요청을_보내고_RMA를_저장한다() {
        Fixture fixture = pendingReturn();
        when(returnPort.create(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(invocation.getArgument(0, CreateRequest.class)).isEqualTo(new CreateRequest(
                    fixture.requestKey(), fixture.orderId(), "상품 불량",
                    List.of(new CreateItem(fixture.orderItemId(), fixture.productId(), 2))));
            return result(fixture);
        });

        returnSubmissionService.submit(fixture.returnId());

        CustomerReturn saved = saved(fixture.returnId());
        assertThat(saved.getRmaId()).isEqualTo(fixture.rmaId());
        assertThat(saved.getStatus()).isEqualTo(CustomerReturnStatus.REQUESTED);
    }

    @Test
    void 응답을_잃어_같은_요청을_재전송해도_기존_RMA로_수렴한다() {
        Fixture fixture = pendingReturn();
        when(returnPort.create(any()))
                .thenThrow(new TransientReturnFailure(new IllegalStateException("timeout")))
                .thenReturn(result(fixture));

        returnSubmissionService.submit(fixture.returnId());
        assertThat(saved(fixture.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.PENDING_SUBMISSION);

        returnSubmissionService.submit(fixture.returnId());

        CustomerReturn saved = saved(fixture.returnId());
        assertThat(saved.getRmaId()).isEqualTo(fixture.rmaId());
        assertThat(saved.getStatus()).isEqualTo(CustomerReturnStatus.REQUESTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BAD_REQUEST", "CONFLICT"})
    void 영구_거절은_접수실패로_저장한다(String code) {
        Fixture fixture = pendingReturn();
        when(returnPort.create(any())).thenThrow(new ReturnPort.PermanentReturnRejection(code));

        returnSubmissionService.submit(fixture.returnId());

        CustomerReturn saved = saved(fixture.returnId());
        assertThat(saved.getStatus()).isEqualTo(CustomerReturnStatus.SUBMISSION_FAILED);
        assertThat(saved.getFailureReason()).isEqualTo(code);
    }

    @ParameterizedTest
    @MethodSource("retryableFailures")
    void 일시_오류와_인증_오류는_접수대기를_유지한다(RuntimeException failure) {
        Fixture fixture = pendingReturn();
        when(returnPort.create(any())).thenThrow(failure);

        returnSubmissionService.submit(fixture.returnId());

        CustomerReturn saved = saved(fixture.returnId());
        assertThat(saved.getStatus()).isEqualTo(CustomerReturnStatus.PENDING_SUBMISSION);
        assertThat(saved.getRmaId()).isNull();
    }

    @Test
    void POST_응답보다_콜백이_먼저_상태를_진전시켜도_REQUESTED로_되돌리지_않는다() {
        Fixture fixture = pendingReturn();
        when(returnPort.create(any())).thenAnswer(invocation -> {
            customerReturnService.markRequested(fixture.returnId(), fixture.rmaId());
            transactionTemplate.executeWithoutResult(status ->
                    customerReturnRepository.findDetailedById(fixture.returnId()).orElseThrow().markReceived());
            return result(fixture);
        });

        returnSubmissionService.submit(fixture.returnId());

        CustomerReturn saved = saved(fixture.returnId());
        assertThat(saved.getRmaId()).isEqualTo(fixture.rmaId());
        assertThat(saved.getStatus()).isEqualTo(CustomerReturnStatus.RECEIVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BAD_REQUEST", "CONFLICT"})
    void 콜백이_완료시킨_뒤_도착한_영구거절은_no_op이다(String code) {
        Fixture fixture = pendingReturn();
        when(returnPort.create(any())).thenAnswer(invocation -> {
            transactionTemplate.executeWithoutResult(status -> {
                CustomerReturn customerReturn = saved(fixture.returnId());
                customerReturn.markRequested(fixture.rmaId());
                customerReturn.complete(List.of(new CustomerReturn.ResultItem(
                        fixture.orderItemId(), 1, com.jhg.hgpage.oms.domain.enums.ReturnDisposition.RESTOCKED)));
            });
            throw new ReturnPort.PermanentReturnRejection(code);
        });

        returnSubmissionService.submit(fixture.returnId());

        assertThat(saved(fixture.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.COMPLETED);
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> invalidPostResults() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("requestKey", change(result -> new ReturnResult(
                        result.rmaId(), UUID.randomUUID(), result.orderId(), result.status(), result.items()))),
                org.junit.jupiter.params.provider.Arguments.of("orderId", change(result -> new ReturnResult(
                        result.rmaId(), result.requestKey(), result.orderId() + 1, result.status(), result.items()))),
                org.junit.jupiter.params.provider.Arguments.of("item", change(result -> new ReturnResult(
                        result.rmaId(), result.requestKey(), result.orderId(), result.status(), List.of(
                        new ResultItem(result.items().get(0).orderItemId() + 1,
                                result.items().get(0).productId(), 2, 0, null))))),
                org.junit.jupiter.params.provider.Arguments.of("status", change(result -> new ReturnResult(
                        result.rmaId(), result.requestKey(), result.orderId(), "RETURNED", result.items()))));
    }

    private static java.util.function.UnaryOperator<ReturnResult> change(
            java.util.function.UnaryOperator<ReturnResult> change) {
        return change;
    }

    private static Stream<RuntimeException> retryableFailures() {
        return Stream.of(
                new TransientReturnFailure(new IllegalStateException("network")),
                new ReturnAuthenticationFailure());
    }

    private Fixture pendingReturn() {
        return transactionTemplate.execute(status -> {
            Product product = new Product();
            product.setName("상품");
            product.setPrice(10000);
            em.persist(product);
            Member member = Member.createUser("테스터", "010-0000-0000",
                    new Address("서울", "관악구", "500"));
            em.persist(member);
            Delivery delivery = new Delivery();
            delivery.setAddress(new Address("서울", "관악구", "500"));
            OrderItem item = OrderItem.createOrderItem(product, product.getPrice(), 3);
            Order order = Order.createOrder(member, delivery, item);
            order.ship();
            order.deliver();
            em.persist(order);
            em.flush();
            Long returnId = customerReturnService.request(order.getId(), member.getId(), "상품 불량",
                    List.of(new CustomerReturnService.ReturnLine(item.getId(), 2)));
            customerReturnRepository.findDetailedById(returnId).orElseThrow().approve("admin@example.com");
            UUID requestKey = customerReturnService.pendingSubmission(returnId).requestKey();
            return new Fixture(returnId, requestKey, order.getId(), item.getId(), product.getId(), 1000L + returnId);
        });
    }

    private CustomerReturn saved(Long returnId) {
        return customerReturnRepository.findDetailedById(returnId).orElseThrow();
    }

    private ReturnResult result(Fixture fixture) {
        return new ReturnResult(fixture.rmaId(), fixture.requestKey(), fixture.orderId(), "REQUESTED", List.of(
                new ResultItem(fixture.orderItemId(), fixture.productId(), 2, 0, null)));
    }

    private ReturnResult completedResult(Fixture fixture) {
        return new ReturnResult(fixture.rmaId(), fixture.requestKey(), fixture.orderId(), "COMPLETED", List.of(
                new ResultItem(fixture.orderItemId(), fixture.productId(), 2, 1, "RESTOCKED")));
    }

    private record Fixture(Long returnId, UUID requestKey, Long orderId,
                           Long orderItemId, Long productId, Long rmaId) {}
}
