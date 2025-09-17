package com.jhg.hgpage.repositoey;

import com.jhg.hgpage.domain.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.jhg.hgpage.domain.QOrder.order;
import static com.jhg.hgpage.domain.QOrderItem.orderItem;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query(value = "select o from Order o left outer join fetch o.member m left outer join fetch o.orderItems oi where m.id = :memberId ")
    List<Order> findOrdersByMemberId(Long memberId);

}
