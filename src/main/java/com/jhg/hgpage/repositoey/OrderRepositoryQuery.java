package com.jhg.hgpage.repositoey;

import com.jhg.hgpage.domain.Order;
import com.jhg.hgpage.domain.enums.OrderStatus;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.List;

import static com.jhg.hgpage.domain.QMember.member;
import static com.jhg.hgpage.domain.QOrder.order;

@Repository
@RequiredArgsConstructor
public class OrderRepositoryQuery {

    private final EntityManager em;

    private final JPAQueryFactory jpaQueryFactory;

    public List<Order> findOrders(SearchOption searchOption) {
        return jpaQueryFactory.select(order).from(order).where(statusEq(searchOption.getOrderStatus()), nameLike(searchOption.getUserName())).limit(100).fetch();
    }

    private BooleanExpression statusEq(OrderStatus orderStatus) {
        if(orderStatus == null){
            return null;
        }

        return order.status.eq(orderStatus);
    }

    private BooleanExpression nameLike(String userName) {
        if(!StringUtils.hasText(userName)) {
            return null;
        }

        return member.name.contains(userName);
    }
}
