package com.jhg.hgpage.oms.repository;

import com.jhg.hgpage.oms.domain.Order;
import com.jhg.hgpage.oms.domain.enums.OrderStatus;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.jhg.hgpage.oms.domain.QDelivery.delivery;
import static com.jhg.hgpage.oms.domain.QMember.member;
import static com.jhg.hgpage.oms.domain.QOrder.order;
import static com.jhg.hgpage.oms.domain.QOrderItem.orderItem;
import static com.jhg.hgpage.catalog.QProduct.product;

@Repository
@RequiredArgsConstructor
public class OrderRepositoryQuery {

    private final JPAQueryFactory jpaQueryFactory;

    public List<Order> findOrders(Long memberId) {
        // 컬렉션 fetch join + limit은 limit이 메모리에 적용되므로(HHH90003004),
        // 루트(order)만 limit으로 조회하고 orderItems는 batch fetch(default_batch_fetch_size)에 맡긴다(#9 ②).
        return jpaQueryFactory.selectFrom(order)
                .where(order.member.id.eq(memberId))
                .limit(100)
                .fetch();
    }

    // 관리자 배송 관리 목록 — ToOne(member/delivery)만 fetch join, orderItems는 batch fetch에 맡긴다
    public List<Order> findAllForAdmin() {
        return jpaQueryFactory.selectFrom(order)
                .join(order.member, member).fetchJoin()
                .join(order.delivery, delivery).fetchJoin()
                .orderBy(order.id.desc())
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
                .orderBy(order.id.asc())
                .fetch();
        if (orderIds.isEmpty()) {
            return List.of();
        }

        return jpaQueryFactory.select(order).distinct()
                .from(order)
                .join(order.orderItems, orderItem).fetchJoin()
                .join(orderItem.product, product).fetchJoin()
                .where(order.id.in(orderIds))
                .orderBy(order.id.asc())
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
