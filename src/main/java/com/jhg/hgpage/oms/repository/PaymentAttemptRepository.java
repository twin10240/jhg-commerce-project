package com.jhg.hgpage.oms.repository;

import com.jhg.hgpage.oms.domain.PaymentAttempt;
import com.jhg.hgpage.oms.domain.enums.PaymentAttemptStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

    Optional<PaymentAttempt> findByRequestKey(UUID requestKey);

    @Query("select a.payment.order.id from PaymentAttempt a where a.id = :id")
    Optional<Long> findOrderIdById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentAttempt> findFirstByPaymentOrderIdAndStatusInOrderByIdDesc(
            Long orderId, Collection<PaymentAttemptStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PaymentAttempt a join fetch a.payment p join fetch p.order where a.id = :id")
    Optional<PaymentAttempt> findByIdForUpdate(Long id);

    List<PaymentAttempt> findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderById(
            Collection<PaymentAttemptStatus> statuses, LocalDateTime now);

    @Query("select a.id from PaymentAttempt a where a.status = :status " +
            "and a.updatedAt <= :updatedAt order by a.id")
    List<Long> findIdsByStatusAndUpdatedAtLessThanEqualOrderById(
            PaymentAttemptStatus status, LocalDateTime updatedAt, Pageable pageable);

    @Query("select a.id from PaymentAttempt a join a.payment p join p.order o " +
            "where a.status = com.jhg.hgpage.oms.domain.enums.PaymentAttemptStatus.MANUAL_REVIEW " +
            "and p.status = com.jhg.hgpage.oms.domain.enums.PaymentStatus.PAYMENT_REVIEW " +
            "and o.status = com.jhg.hgpage.oms.domain.enums.OrderStatus.CANCEL_REQUESTED " +
            "and o.cancellationReleaseRequired is null order by a.id")
    List<Long> findCancellationReviewAttemptIds();
}
