package com.jhg.hgpage.oms.repository;

import com.jhg.hgpage.oms.domain.CustomerReturn;
import com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerReturnRepository extends JpaRepository<CustomerReturn, Long> {

    @EntityGraph(attributePaths = {"order", "items", "items.orderItem", "items.orderItem.product"})
    @Query("select distinct r from CustomerReturn r where r.id = :id")
    Optional<CustomerReturn> findDetailedById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CustomerReturn r where r.id = :id")
    Optional<CustomerReturn> findDetailedByIdForUpdate(Long id);

    @EntityGraph(attributePaths = {"order", "items", "items.orderItem", "items.orderItem.product"})
    @Query("select distinct r from CustomerReturn r where r.requestKey = :requestKey")
    Optional<CustomerReturn> findDetailedByRequestKey(UUID requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CustomerReturn r where r.requestKey = :requestKey")
    Optional<CustomerReturn> findDetailedByRequestKeyForUpdate(UUID requestKey);

    Optional<CustomerReturn> findByRmaId(Long rmaId);

    @EntityGraph(attributePaths = {"order", "items", "items.orderItem", "items.orderItem.product"})
    @Query("select distinct r from CustomerReturn r where r.order.id = :orderId order by r.id desc")
    List<CustomerReturn> findDetailedByOrderId(Long orderId);

    @EntityGraph(attributePaths = {"order", "items", "items.orderItem", "items.orderItem.product"})
    @Query("select distinct r from CustomerReturn r where r.status in :statuses order by r.id")
    List<CustomerReturn> findDetailedByStatusIn(Collection<CustomerReturnStatus> statuses);

    @EntityGraph(attributePaths = {"order", "order.member", "items", "items.orderItem", "items.orderItem.product"})
    @Query("""
            select r from CustomerReturn r
            where (:status is null or r.status = :status)
            order by case when r.status = com.jhg.hgpage.oms.domain.enums.CustomerReturnStatus.PENDING_APPROVAL
                          then 0 else 1 end,
                     r.requestedAt, r.id
            """)
    List<CustomerReturn> findAllDetailedForAdmin(CustomerReturnStatus status);
}
