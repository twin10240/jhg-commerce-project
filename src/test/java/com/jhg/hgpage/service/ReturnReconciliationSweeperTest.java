package com.jhg.hgpage.service;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.contract.ReturnPort;
import com.jhg.hgpage.contract.ReturnPort.RemoteReturnNotFound;
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
import com.jhg.hgpage.oms.service.ReturnReconciliationSweeper;
import com.jhg.hgpage.oms.service.ReturnSubmissionService;
import com.jhg.hgpage.oms.service.ReturnSyncService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({CustomerReturnService.class, ReturnSubmissionService.class,
        ReturnSyncService.class, RefundService.class, RetrySchedule.class,
        ReturnReconciliationSweeper.class})
class ReturnReconciliationSweeperTest {

    @Autowired ReturnReconciliationSweeper sweeper;
    @Autowired CustomerReturnRepository customerReturnRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManager em;
    @MockitoBean ReturnPort returnPort;

    @BeforeEach
    void clearReturns() {
        transactionTemplate.executeWithoutResult(status -> customerReturnRepository.deleteAll());
    }

    @Test
    void 대상이_없으면_WMS를_호출하지_않는다() {
        sweeper.sweep();

        verifyNoInteractions(returnPort);
    }

    @Test
    void 접수대상_스캔이_실패해도_활성_RMA_스캔은_계속한다() {
        CustomerReturnService customerReturnService = mock(CustomerReturnService.class);
        ReturnSubmissionService submissionService = mock(ReturnSubmissionService.class);
        ReturnSyncService syncService = mock(ReturnSyncService.class);
        ReturnPort port = mock(ReturnPort.class);
        ReturnResult result = mock(ReturnResult.class);
        when(customerReturnService.pendingSubmissionIds()).thenThrow(new IllegalStateException("scan"));
        when(customerReturnService.activeReturns()).thenReturn(List.of(
                new CustomerReturnService.ActiveReturn(1L, 10L)));
        when(port.find(10L)).thenReturn(result);
        ReturnReconciliationSweeper isolated = new ReturnReconciliationSweeper(
                customerReturnService, submissionService, syncService, port);

        isolated.sweep();

        verify(syncService).apply(result);
    }

    @Test
    void 접수대기는_트랜잭션_밖에서_재전송한다() {
        Fixture fixture = savedReturn(CustomerReturnStatus.PENDING_SUBMISSION);
        when(returnPort.create(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return result(fixture, "REQUESTED");
        });

        sweeper.sweep();

        assertThat(saved(fixture.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.REQUESTED);
    }

    @Test
    void 요청과_수령상태는_트랜잭션_밖에서_조회한_뒤_완료결과를_적용한다() {
        Fixture requested = savedReturn(CustomerReturnStatus.REQUESTED);
        Fixture received = savedReturn(CustomerReturnStatus.RECEIVED);
        when(returnPort.find(requested.rmaId())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return result(requested, "COMPLETED");
        });
        when(returnPort.find(received.rmaId())).thenReturn(result(received, "COMPLETED"));

        sweeper.sweep();

        assertThat(saved(requested.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.COMPLETED);
        assertThat(saved(received.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.COMPLETED);
    }

    @Test
    void 한_접수의_예상밖_오류가_다음_접수를_막지_않는다() {
        Fixture broken = savedReturn(CustomerReturnStatus.PENDING_SUBMISSION);
        Fixture healthy = savedReturn(CustomerReturnStatus.PENDING_SUBMISSION);
        when(returnPort.create(any())).thenAnswer(invocation -> {
            ReturnPort.CreateRequest request = invocation.getArgument(0);
            if (request.requestKey().equals(broken.requestKey())) throw new IllegalStateException("unexpected");
            return result(healthy, "REQUESTED");
        });

        sweeper.sweep();

        assertThat(saved(broken.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.PENDING_SUBMISSION);
        assertThat(saved(healthy.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.REQUESTED);
    }

    @Test
    void 조회_오류와_계약불일치는_각_RMA에_격리되어_다음_결과를_계속_적용한다() {
        Fixture network = savedReturn(CustomerReturnStatus.REQUESTED);
        Fixture authentication = savedReturn(CustomerReturnStatus.RECEIVED);
        Fixture missing = savedReturn(CustomerReturnStatus.REQUESTED);
        Fixture mismatch = savedReturn(CustomerReturnStatus.REQUESTED);
        Fixture unexpected = savedReturn(CustomerReturnStatus.REQUESTED);
        Fixture healthy = savedReturn(CustomerReturnStatus.REQUESTED);
        when(returnPort.find(network.rmaId()))
                .thenThrow(new TransientReturnFailure(new IllegalStateException("network")));
        when(returnPort.find(authentication.rmaId())).thenThrow(new ReturnAuthenticationFailure());
        when(returnPort.find(missing.rmaId())).thenThrow(new RemoteReturnNotFound(missing.rmaId()));
        ReturnResult validMismatch = result(mismatch, "COMPLETED");
        when(returnPort.find(mismatch.rmaId())).thenReturn(new ReturnResult(
                validMismatch.rmaId(), validMismatch.requestKey(), validMismatch.orderId() + 1,
                validMismatch.status(), validMismatch.items()));
        when(returnPort.find(unexpected.rmaId())).thenThrow(new IllegalStateException("unexpected"));
        when(returnPort.find(healthy.rmaId())).thenReturn(result(healthy, "COMPLETED"));

        sweeper.sweep();

        assertThat(saved(network.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.REQUESTED);
        assertThat(saved(authentication.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.RECEIVED);
        assertThat(saved(missing.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.REQUESTED);
        assertThat(saved(mismatch.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.REQUESTED);
        assertThat(saved(unexpected.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.REQUESTED);
        assertThat(saved(healthy.returnId()).getStatus()).isEqualTo(CustomerReturnStatus.COMPLETED);
    }

    private Fixture savedReturn(CustomerReturnStatus status) {
        return transactionTemplate.execute(transaction -> {
            Product product = new Product();
            product.setName("상품");
            product.setPrice(10000);
            em.persist(product);
            Member member = Member.createUser("테스터", "010-0000-0000",
                    new Address("서울", "관악구", "500"));
            em.persist(member);
            Delivery delivery = new Delivery();
            delivery.setAddress(new Address("서울", "관악구", "500"));
            OrderItem item = OrderItem.createOrderItem(product, product.getPrice(), 2);
            Order order = Order.createOrder(member, delivery, item);
            order.ship();
            order.deliver();
            em.persist(order);
            em.flush();
            UUID requestKey = UUID.randomUUID();
            Long rmaId = 1000L + order.getId();
            CustomerReturn customerReturn = CustomerReturn.create(order, requestKey, "불량",
                    List.of(new CustomerReturn.RequestItem(item, 2)));
            if (status != CustomerReturnStatus.PENDING_SUBMISSION) {
                customerReturn.markRequested(rmaId);
            }
            if (status == CustomerReturnStatus.RECEIVED) {
                customerReturn.markReceived();
            }
            em.persist(customerReturn);
            em.flush();
            return new Fixture(customerReturn.getId(), requestKey, rmaId, order.getId(), item.getId(), product.getId());
        });
    }

    private CustomerReturn saved(Long returnId) {
        return customerReturnRepository.findDetailedById(returnId).orElseThrow();
    }

    private ReturnResult result(Fixture fixture, String status) {
        boolean completed = status.equals("COMPLETED");
        return new ReturnResult(fixture.rmaId(), fixture.requestKey(), fixture.orderId(), status, List.of(
                new ResultItem(fixture.orderItemId(), fixture.productId(), 2,
                        completed ? 1 : 0, completed ? "RESTOCKED" : null)));
    }

    private record Fixture(Long returnId, UUID requestKey, Long rmaId, Long orderId,
                           Long orderItemId, Long productId) {}
}
