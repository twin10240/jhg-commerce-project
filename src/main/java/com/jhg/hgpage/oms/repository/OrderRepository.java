package com.jhg.hgpage.oms.repository;

import com.jhg.hgpage.oms.domain.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :orderId")
    Optional<Order> findByIdForUpdate(@Param("orderId") Long orderId);

    @Query("select o.id from Order o where o.status = com.jhg.hgpage.oms.domain.enums.OrderStatus.CANCEL_REQUESTED " +
            "and o.cancellationReleaseRequired is not null and o.cancellationProcessingAt is null " +
            "order by o.cancellationRequestedAt, o.id")
    List<Long> findDueCancellationOrderIds();

    @Query("select o.id from Order o where o.status = com.jhg.hgpage.oms.domain.enums.OrderStatus.CANCEL_REQUESTED " +
            "and o.cancellationProcessingAt <= :staleBefore order by o.cancellationRequestedAt, o.id")
    List<Long> findStaleCancellationOrderIds(@Param("staleBefore") LocalDateTime staleBefore);

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
