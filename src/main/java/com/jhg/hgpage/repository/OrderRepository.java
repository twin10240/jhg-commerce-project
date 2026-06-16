package com.jhg.hgpage.repository;

import com.jhg.hgpage.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * [학습용 보존 — 실사용 아님] 주문 상세 단건 조회의 JPQL 버전.
     * 실제 사용처는 같은 쿼리의 QueryDSL 버전인 {@link OrderRepositoryQuery#findDetailById}이며,
     * JPQL과 QueryDSL의 fetch join 작성법을 비교하기 위해 의도적으로 남겨둔 코드다(죽은 코드 아님).
     */
    @Query("select o from Order o" +
            " join fetch o.member" +
            " join fetch o.delivery" +
            " join fetch o.orderItems oi" +
            " join fetch oi.product" +
            " where o.id = :orderId")
    Optional<Order> findDetailById(Long orderId);
}
