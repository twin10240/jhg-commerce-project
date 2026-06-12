package com.jhg.hgpage.repository;

import com.jhg.hgpage.domain.Order;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.jhg.hgpage.domain.QDelivery.delivery;
import static com.jhg.hgpage.domain.QMember.member;
import static com.jhg.hgpage.domain.QOrder.order;
import static com.jhg.hgpage.domain.QOrderItem.orderItem;
import static com.jhg.hgpage.domain.QProduct.product;

@Repository
@RequiredArgsConstructor
public class OrderRepositoryQuery {

    private final JPAQueryFactory jpaQueryFactory;

    public List<Order> findOrders(Long memberId) {
        return jpaQueryFactory.select(order).distinct()
                .from(order)
                .join(order.orderItems, orderItem).fetchJoin()
                .where(order.member.id.eq(memberId))
                .limit(100)
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
