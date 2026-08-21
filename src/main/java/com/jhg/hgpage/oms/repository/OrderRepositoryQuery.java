package com.jhg.hgpage.oms.repository;

import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.Payment;
import com.jhg.hgpage.oms.domain.PaymentAttempt;
import com.jhg.hgpage.oms.domain.RefundRequest;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.jhg.hgpage.oms.domain.enums.DeliveryStatus;
import com.jhg.hgpage.oms.domain.enums.PaymentStatus;
import com.jhg.hgpage.oms.domain.enums.RefundStatus;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.jhg.hgpage.oms.domain.QDelivery.delivery;
import static com.jhg.hgpage.oms.domain.QMember.member;
import static com.jhg.hgpage.oms.domain.QOrder.order;
import static com.jhg.hgpage.oms.domain.QOrderItem.orderItem;
import static com.jhg.hgpage.oms.domain.QPayment.payment;
import static com.jhg.hgpage.oms.domain.QPaymentAttempt.paymentAttempt;
import static com.jhg.hgpage.oms.domain.QRefundRequest.refundRequest;
import static com.jhg.hgpage.catalog.QProduct.product;

@Repository
@RequiredArgsConstructor
public class OrderRepositoryQuery {

    private final JPAQueryFactory jpaQueryFactory;

    public List<Order> findOrders(Long memberId) {
        // 컬렉션 fetch join + limit은 limit이 메모리에 적용되므로(HHH90003004),
        // 루트(order)만 limit으로 조회하고 orderItems는 batch fetch(default_batch_fetch_size)에 맡긴다(#9 ②).
        var completed = new CaseBuilder()
                .when(order.status.eq(OrderStatus.CANCEL)
                        .or(delivery.status.eq(DeliveryStatus.DELIVERED))).then(1)
                .otherwise(0);
        return jpaQueryFactory.selectFrom(order)
                .join(order.delivery, delivery)
                .where(order.member.id.eq(memberId))
                .orderBy(completed.asc(), order.orderDate.desc(), order.id.desc())
                .limit(100)
                .fetch();
    }

    public List<Payment> findPaymentsByOrderIds(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return jpaQueryFactory.selectFrom(payment)
                .where(payment.order.id.in(orderIds))
                .fetch();
    }

    public Optional<Payment> findPaymentByOrderId(Long orderId) {
        return Optional.ofNullable(jpaQueryFactory.selectFrom(payment)
                .where(payment.order.id.eq(orderId))
                .fetchOne());
    }

    public List<Payment> findPaymentsForAdmin(PaymentStatus status) {
        return jpaQueryFactory.selectFrom(payment)
                .join(payment.order, order).fetchJoin()
                .where(status == null ? null : payment.status.eq(status))
                .orderBy(payment.updatedAt.desc(), payment.id.desc())
                .fetch();
    }

    public List<PaymentAttempt> findAttemptsForPaymentIds(Collection<Long> paymentIds) {
        if (paymentIds.isEmpty()) {
            return List.of();
        }
        return jpaQueryFactory.selectFrom(paymentAttempt)
                .join(paymentAttempt.payment, payment).fetchJoin()
                .where(paymentAttempt.payment.id.in(paymentIds))
                .orderBy(paymentAttempt.id.desc())
                .fetch();
    }

    public List<RefundRequest> findRefundsForAdmin(RefundStatus status) {
        return jpaQueryFactory.selectFrom(refundRequest)
                .join(refundRequest.payment, payment).fetchJoin()
                .join(payment.order, order).fetchJoin()
                .where(status == null ? null : refundRequest.status.eq(status))
                .orderBy(refundRequest.updatedAt.desc(), refundRequest.id.desc())
                .fetch();
    }

    public long countRefundReviews() {
        Long count = jpaQueryFactory.select(refundRequest.count())
                .from(refundRequest)
                .where(refundRequest.status.eq(RefundStatus.MANUAL_REVIEW))
                .fetchOne();
        return count == null ? 0 : count;
    }

    public long countAllocationReviews() {
        Long count = jpaQueryFactory.select(order.count())
                .from(order)
                .where(order.status.eq(OrderStatus.ALLOCATION_REVIEW))
                .fetchOne();
        return count == null ? 0 : count;
    }

    // 관리자 배송 관리 목록 — ToOne(member/delivery)만 fetch join, orderItems는 batch fetch에 맡긴다
    public List<Order> findAllForAdmin() {
        var priority = new CaseBuilder()
                .when(order.status.eq(OrderStatus.ORDER)
                        .and(delivery.status.eq(DeliveryStatus.READY))).then(0)
                .when(order.status.eq(OrderStatus.BACKORDERED)).then(1)
                .when(delivery.status.eq(DeliveryStatus.SHIPPED)).then(2)
                .when(delivery.status.eq(DeliveryStatus.DELIVERED)).then(3)
                .otherwise(4);
        var activeAge = new CaseBuilder()
                .when(order.status.eq(OrderStatus.ORDER)
                        .and(delivery.status.eq(DeliveryStatus.READY))
                        .or(order.status.eq(OrderStatus.BACKORDERED))).then(order.id)
                .otherwise(Long.MAX_VALUE);

        return jpaQueryFactory.selectFrom(order)
                .join(order.member, member).fetchJoin()
                .join(order.delivery, delivery).fetchJoin()
                .orderBy(priority.asc(), activeAge.asc(), order.id.desc())
                .fetch();
    }

    /**
     * 입고 시 백오더 자동 할당용 — 해당 상품을 포함하는 BACKORDERED 주문을 오래된 순(FIFO)으로 반환.
     * fetch join에 상품 필터를 같이 걸면 컬렉션이 매칭 라인만 남도록 잘리므로,
     * 1) 필터로 주문 id만 추리고 2) id로 모든 라인을 fetch join하는 2단계로 조회한다.
     */
    public List<Order> findBackordersContaining(Collection<Long> productIds) {
        List<Long> orderIds = jpaQueryFactory.select(order.id).distinct()
                .from(order)
                .join(order.orderItems, orderItem)
                .where(order.status.eq(OrderStatus.BACKORDERED),
                        orderItem.product.id.in(productIds))
                .fetch();
        if (orderIds.isEmpty()) {
            return List.of();
        }

        return jpaQueryFactory.select(order).distinct()
                .from(order)
                .join(order.orderItems, orderItem).fetchJoin()
                .join(orderItem.product, product).fetchJoin()
                .where(order.id.in(orderIds))
                .orderBy(order.orderDate.asc(), order.id.asc())
                .fetch();
    }

    public List<Order> findPaidBackordersContaining(Collection<Long> productIds) {
        List<Long> orderIds = jpaQueryFactory.select(order.id).distinct()
                .from(order)
                .join(order.orderItems, orderItem)
                .where(order.status.eq(OrderStatus.BACKORDERED),
                        order.delivery.status.eq(DeliveryStatus.READY),
                        orderItem.product.id.in(productIds),
                        JPAExpressions.selectOne()
                                .from(payment)
                                .where(payment.order.eq(order))
                                .notExists()
                                .or(JPAExpressions.selectOne()
                                        .from(payment)
                                        .where(payment.order.eq(order), payment.status.eq(PaymentStatus.PAID))
                                        .exists()))
                .fetch();
        if (orderIds.isEmpty()) {
            return List.of();
        }

        return jpaQueryFactory.select(order).distinct()
                .from(order)
                .where(order.id.in(orderIds))
                .orderBy(order.orderDate.asc(), order.id.asc())
                .fetch();
    }

    public List<Long> findDueAllocationOrderIds(LocalDateTime now) {
        return jpaQueryFactory.select(order.id)
                .from(order)
                .where(order.status.eq(OrderStatus.ALLOCATION_PENDING)
                                .or(order.status.eq(OrderStatus.CANCEL_REQUESTED)
                                        .and(order.cancellationReleaseRequired.isNull())
                                        .and(order.allocationAttemptCount.gt(0))),
                        order.nextAllocationAttemptAt.loe(now))
                .orderBy(order.orderDate.asc(), order.id.asc())
                .limit(50)
                .fetch();
    }

    public List<Long> findStaleAllocationOrderIds(LocalDateTime staleBefore) {
        return jpaQueryFactory.select(order.id)
                .from(order)
                .where(order.status.eq(OrderStatus.ALLOCATION_PROCESSING)
                                .or(order.status.eq(OrderStatus.CANCEL_REQUESTED)
                                        .and(order.cancellationReleaseRequired.isNull())
                                        .and(order.allocationAttemptCount.gt(0))),
                        order.allocationProcessingAt.loe(staleBefore))
                .orderBy(order.orderDate.asc(), order.id.asc())
                .limit(50)
                .fetch();
    }

    public List<Long> findCancellationAllocationReviewOrderIds() {
        return jpaQueryFactory.select(order.id)
                .from(order)
                .where(order.status.eq(OrderStatus.CANCEL_REQUESTED),
                        order.cancellationReleaseRequired.isNull()
                                .and(order.allocationAttemptCount.gt(0))
                                .and(order.nextAllocationAttemptAt.isNull())
                                .and(order.allocationProcessingAt.isNull())
                                .or(order.cancellationReleaseRequired.isTrue()
                                        .and(order.cancellationAttemptCount.gt(0))
                                        .and(order.cancellationNextAttemptAt.isNull())
                                        .and(order.cancellationProcessingAt.isNull())))
                .orderBy(order.cancellationRequestedAt.asc(), order.id.asc())
                .fetch();
    }

    /** 보상 스윕(S4)용 — BACKORDERED 주문이 기다리는 상품 id 목록(중복 제거). */
    public List<Long> findBackorderedProductIds() {
        return jpaQueryFactory.select(orderItem.product.id).distinct()
                .from(order)
                .join(order.orderItems, orderItem)
                .where(order.status.eq(OrderStatus.BACKORDERED))
                .fetch();
    }

    // 주문 상세 페이지용 단건 조회 (인가 체크를 위해 member도 함께 로딩)
    public Optional<Order> findDetailById(Long orderId) {
        return Optional.ofNullable(jpaQueryFactory.selectFrom(order)
                .join(order.member, member).fetchJoin()
                .join(order.delivery, delivery).fetchJoin()
                .join(order.orderItems, orderItem).fetchJoin()
                .join(orderItem.product, product).fetchJoin()
                .where(order.id.eq(orderId))
                .fetchOne());
    }
}
