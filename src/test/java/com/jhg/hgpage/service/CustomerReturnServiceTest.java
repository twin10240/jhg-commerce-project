package com.jhg.hgpage.service;

import com.jhg.hgpage.catalog.Product;
import com.jhg.hgpage.exception.EntityNotFoundException;
import com.jhg.hgpage.oms.domain.Address;
import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.Delivery;
import com.jhg.hgpage.oms.domain.Member;
import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.OrderItem;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import com.jhg.hgpage.oms.domain.enums.ReturnDisposition;
import com.jhg.hgpage.oms.dto.AdminCustomerReturnDto;
import com.jhg.hgpage.oms.repository.CustomerReturnRepository;
import com.jhg.hgpage.oms.service.CustomerReturnService;
import com.jhg.hgpage.realtime.outbox.NotificationEventWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(CustomerReturnService.class)
class CustomerReturnServiceTest {

    @Autowired CustomerReturnService customerReturnService;
    @Autowired CustomerReturnRepository customerReturnRepository;
    @Autowired TestEntityManager em;
    @MockitoBean NotificationEventWriter eventWriter;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = deliveredOrder(4);
    }

    @Test
    void 배송완료_주문의_반품요청을_한_UUID의_OMS승인대기로_저장한다() {
        Long returnId = customerReturnService.request(fixture.order().getId(), fixture.member().getId(),
                "  상품 불량  ", List.of(new CustomerReturnService.ReturnLine(fixture.item().getId(), 2)));

        em.flush();
        em.clear();
        CustomerReturn saved = customerReturnRepository.findDetailedById(returnId).orElseThrow();

        assertThat(saved.getRequestKey()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(CustomerReturnStatus.PENDING_APPROVAL);
        assertThat(saved.getReason()).isEqualTo("상품 불량");
        assertThatThrownBy(() -> customerReturnService.pendingSubmission(returnId))
                .isInstanceOf(IllegalStateException.class);

        customerReturnService.approveReturn(returnId, "admin@example.com");

        assertThat(customerReturnService.pendingSubmission(returnId).returnId()).isEqualTo(returnId);
    }

    @Test
    void 배송완료가_아닌_주문은_거절한다() {
        Fixture ready = order(1, false);

        assertThatThrownBy(() -> customerReturnService.request(ready.order().getId(), ready.member().getId(),
                "불량", List.of(new CustomerReturnService.ReturnLine(ready.item().getId(), 1))))
                .isInstanceOf(IllegalArgumentException.class);

        ready.order().ship();
        em.flush();
        assertThatThrownBy(() -> customerReturnService.request(ready.order().getId(), ready.member().getId(),
                "불량", List.of(new CustomerReturnService.ReturnLine(ready.item().getId(), 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 타인_주문은_404로_숨긴다() {
        Member other = saveMember("타인");

        assertThatThrownBy(() -> customerReturnService.request(fixture.order().getId(), other.getId(),
                "불량", List.of(new CustomerReturnService.ReturnLine(fixture.item().getId(), 1))))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void 빈_사유는_거절한다(String reason) {
        assertInvalid(() -> customerReturnService.request(fixture.order().getId(), fixture.member().getId(),
                reason, List.of(new CustomerReturnService.ReturnLine(fixture.item().getId(), 1))));
    }

    @Test
    void 사유가_500자를_넘으면_거절한다() {
        assertInvalid(() -> customerReturnService.request(fixture.order().getId(), fixture.member().getId(),
                "가".repeat(501), List.of(new CustomerReturnService.ReturnLine(fixture.item().getId(), 1))));
    }

    @Test
    void 반품_품목이_없으면_거절한다() {
        assertInvalid(() -> customerReturnService.request(
                fixture.order().getId(), fixture.member().getId(), "불량", null));
        assertInvalid(() -> customerReturnService.request(
                fixture.order().getId(), fixture.member().getId(), "불량", List.of()));
    }

    @Test
    void 주문에_없는_품목은_거절한다() {
        assertInvalid(() -> customerReturnService.request(fixture.order().getId(), fixture.member().getId(),
                "불량", List.of(new CustomerReturnService.ReturnLine(Long.MAX_VALUE, 1))));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 양수가_아닌_수량은_거절한다(int quantity) {
        assertInvalid(() -> customerReturnService.request(fixture.order().getId(), fixture.member().getId(),
                "불량", List.of(new CustomerReturnService.ReturnLine(fixture.item().getId(), quantity))));
    }

    @Test
    void 같은_주문품목을_두번_요청하면_거절한다() {
        assertInvalid(() -> customerReturnService.request(fixture.order().getId(), fixture.member().getId(),
                "불량", List.of(
                        new CustomerReturnService.ReturnLine(fixture.item().getId(), 1),
                        new CustomerReturnService.ReturnLine(fixture.item().getId(), 1))));
    }

    @Test
    void 완료_반품은_요청수량이_아니라_승인수량만_누적한다() {
        CustomerReturn completed = savedReturn(fixture, 4);
        completed.markRequested(10L);
        completed.complete(List.of(new CustomerReturn.ResultItem(
                fixture.item().getId(), 1, ReturnDisposition.RESTOCKED)));
        em.flush();

        customerReturnService.request(fixture.order().getId(), fixture.member().getId(), "추가 불량",
                List.of(new CustomerReturnService.ReturnLine(fixture.item().getId(), 3)));

        assertInvalid(() -> customerReturnService.request(fixture.order().getId(), fixture.member().getId(),
                "초과", List.of(new CustomerReturnService.ReturnLine(fixture.item().getId(), 1))));
    }

    @Test
    void 진행중_반품은_요청수량을_누적한다() {
        savedReturn(fixture, 1);
        CustomerReturn requested = savedReturn(fixture, 1);
        requested.markRequested(11L);
        CustomerReturn received = savedReturn(fixture, 1);
        received.markRequested(12L);
        received.markReceived();
        em.flush();

        customerReturnService.request(fixture.order().getId(), fixture.member().getId(), "마지막 1개",
                List.of(new CustomerReturnService.ReturnLine(fixture.item().getId(), 1)));

        assertInvalid(() -> customerReturnService.request(fixture.order().getId(), fixture.member().getId(),
                "초과", List.of(new CustomerReturnService.ReturnLine(fixture.item().getId(), 1))));
    }

    @Test
    void 취소와_접수실패_반품은_누적에서_제외한다() {
        Fixture one = deliveredOrder(1);
        CustomerReturn cancelled = savedReturn(one, 1);
        cancelled.cancel();
        CustomerReturn failed = savedReturn(one, 1);
        failed.failSubmission("INVALID");
        em.flush();

        Long returnId = customerReturnService.request(one.order().getId(), one.member().getId(), "재요청",
                List.of(new CustomerReturnService.ReturnLine(one.item().getId(), 1)));

        assertThat(returnId).isNotNull();
    }

    @Test
    void 반려된_반품_수량은_새_요청에_사용할_수_있다() {
        Fixture one = deliveredOrder(1);
        Long rejectedId = customerReturnService.request(one.order().getId(), one.member().getId(), "불량",
                List.of(new CustomerReturnService.ReturnLine(one.item().getId(), 1)));

        customerReturnService.rejectReturn(rejectedId, "admin@example.com", "정책상 반품 불가");

        Long retryId = customerReturnService.request(one.order().getId(), one.member().getId(), "상세 사유 보완",
                List.of(new CustomerReturnService.ReturnLine(one.item().getId(), 1)));

        assertThat(retryId).isNotNull();
    }

    @Test
    void 관리자는_승인대기_반품의_고객과_요청수량을_조회한다() {
        customerReturnService.request(fixture.order().getId(), fixture.member().getId(), "불량",
                List.of(new CustomerReturnService.ReturnLine(fixture.item().getId(), 2)));

        assertThat(customerReturnService.findAllForAdmin(CustomerReturnStatus.PENDING_APPROVAL))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.customerName()).isEqualTo("테스터");
                    assertThat(row.statusLabel()).isEqualTo("OMS 승인 대기");
                    assertThat(row.items()).singleElement()
                            .extracting(AdminCustomerReturnDto.Item::quantity).isEqualTo(2);
                });
    }

    @Test
    void 상태변경과_스윕조회는_정확한_대상만_반환한다() {
        Long requestedId = customerReturnService.request(fixture.order().getId(), fixture.member().getId(), "불량1",
                List.of(new CustomerReturnService.ReturnLine(fixture.item().getId(), 1)));
        Long receivedId = customerReturnService.request(fixture.order().getId(), fixture.member().getId(), "불량2",
                List.of(new CustomerReturnService.ReturnLine(fixture.item().getId(), 1)));
        Long failedId = customerReturnService.request(fixture.order().getId(), fixture.member().getId(), "불량3",
                List.of(new CustomerReturnService.ReturnLine(fixture.item().getId(), 1)));

        customerReturnRepository.findDetailedById(requestedId).orElseThrow().approve("admin@example.com");
        customerReturnRepository.findDetailedById(receivedId).orElseThrow().approve("admin@example.com");
        customerReturnRepository.findDetailedById(failedId).orElseThrow().approve("admin@example.com");
        customerReturnService.markRequested(requestedId, 101L);
        customerReturnService.markRequested(receivedId, 102L);
        customerReturnRepository.findDetailedById(receivedId).orElseThrow().markReceived();
        customerReturnService.markSubmissionFailed(failedId, "INVALID");
        em.flush();

        assertThat(customerReturnService.pendingSubmissionIds()).isEmpty();
        assertThat(customerReturnService.activeReturns()).containsExactly(
                new CustomerReturnService.ActiveReturn(requestedId, 101L),
                new CustomerReturnService.ActiveReturn(receivedId, 102L));
    }

    @Test
    void markRequested는_RMA를_멱등_바인딩하고_나중상태를_되돌리지_않는다() {
        Long returnId = customerReturnService.request(fixture.order().getId(), fixture.member().getId(), "불량",
                List.of(new CustomerReturnService.ReturnLine(fixture.item().getId(), 1)));

        customerReturnRepository.findDetailedById(returnId).orElseThrow().approve("admin@example.com");
        customerReturnService.markRequested(returnId, 101L);
        customerReturnService.markRequested(returnId, 101L);
        CustomerReturn customerReturn = customerReturnRepository.findDetailedById(returnId).orElseThrow();
        customerReturn.markReceived();
        customerReturnService.markRequested(returnId, 101L);

        assertThat(customerReturn.getStatus()).isEqualTo(CustomerReturnStatus.RECEIVED);
        assertThatThrownBy(() -> customerReturnService.markRequested(returnId, 999L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 소유한_반품과_주문의_반품만_조회한다() {
        Long returnId = customerReturnService.request(fixture.order().getId(), fixture.member().getId(), "불량",
                List.of(new CustomerReturnService.ReturnLine(fixture.item().getId(), 1)));
        Member other = saveMember("타인");

        assertThat(customerReturnService.findOwned(returnId, fixture.member().getId()).getId()).isEqualTo(returnId);
        assertThat(customerReturnService.findForOwnedOrder(fixture.order().getId(), fixture.member().getId()))
                .extracting(CustomerReturn::getId).containsExactly(returnId);
        assertThatThrownBy(() -> customerReturnService.findOwned(returnId, other.getId()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> customerReturnService.findForOwnedOrder(fixture.order().getId(), other.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    private CustomerReturn savedReturn(Fixture target, int quantity) {
        CustomerReturn customerReturn = CustomerReturn.create(target.order(), UUID.randomUUID(), "기존 반품",
                List.of(new CustomerReturn.RequestItem(target.item(), quantity)));
        customerReturn.approve("admin@example.com");
        return customerReturnRepository.save(customerReturn);
    }

    private Fixture deliveredOrder(int quantity) {
        return order(quantity, true);
    }

    private Fixture order(int quantity, boolean delivered) {
        Product product = new Product();
        product.setName("상품");
        product.setPrice(10000);
        em.persist(product);
        Member member = saveMember("테스터");
        Delivery delivery = new Delivery();
        delivery.setAddress(new Address("서울", "관악구", "500"));
        OrderItem item = OrderItem.createOrderItem(product, product.getPrice(), quantity);
        Order order = Order.createOrder(member, delivery, item);
        if (delivered) {
            order.ship();
            order.deliver();
        }
        em.persist(order);
        em.flush();
        return new Fixture(member, order, item);
    }

    private Member saveMember(String name) {
        Member member = Member.createUser(name, "010-0000-0000", new Address("서울", "관악구", "500"));
        em.persist(member);
        em.flush();
        return member;
    }

    private void assertInvalid(Supplier<?> request) {
        assertThatThrownBy(request::get).isInstanceOf(IllegalArgumentException.class);
    }

    private record Fixture(Member member, Order order, OrderItem item) {}
}
