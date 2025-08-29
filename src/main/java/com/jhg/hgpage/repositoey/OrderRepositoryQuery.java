package com.jhg.hgpage.repositoey;

import com.jhg.hgpage.domain.Order;
import com.jhg.hgpage.domain.enums.OrderStatus;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.List;

import static com.jhg.hgpage.domain.QMember.member;
import static com.jhg.hgpage.domain.QOrder.order;
import static com.jhg.hgpage.domain.QOrderItem.orderItem;

@Repository
@RequiredArgsConstructor
public class OrderRepositoryQuery {

    private final EntityManager em;

    private final JPAQueryFactory jpaQueryFactory;

    public List<Order> findOrders(Long memberId) {
        return jpaQueryFactory.select(order)
                .from(order)
                .join(order.orderItems, orderItem).fetchJoin()
                .where(order.member.id.eq(memberId))
                .limit(100)
                .fetch();
    }

//    public List<Order> findOrders(SearchOption searchOption, Long memberId) {
//        return jpaQueryFactory.select(order)
//                              .from(order)
//                              .where(statusEq(searchOption.getOrderStatus()), productLike(searchOption.getProductName()) ,order.member.id.eq(memberId)).limit(100).fetch();
//    }

    private BooleanExpression statusEq(OrderStatus orderStatus) {
        if(orderStatus == null){
            return null;
        }

        return order.status.eq(orderStatus);
    }

    private BooleanExpression productLike(String productName) {
        if(!StringUtils.hasText(productName)) {
            return null;
        }

        return member.name.contains(productName);
    }
}
