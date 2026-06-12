package com.jhg.hgpage.repository;

import com.jhg.hgpage.domain.Order;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.jhg.hgpage.domain.QOrder.order;
import static com.jhg.hgpage.domain.QOrderItem.orderItem;

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

}
