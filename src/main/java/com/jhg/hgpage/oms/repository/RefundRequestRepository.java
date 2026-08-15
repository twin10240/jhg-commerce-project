package com.jhg.hgpage.oms.repository;

import com.jhg.hgpage.oms.domain.RefundRequest;
import com.jhg.hgpage.oms.domain.enums.RefundSourceType;
import com.jhg.hgpage.oms.domain.enums.RefundStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {

    Optional<RefundRequest> findBySourceTypeAndSourceId(RefundSourceType type, Long sourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefundRequest r join fetch r.payment p join fetch p.order where r.id = :id")
    Optional<RefundRequest> findByIdForUpdate(Long id);

    List<RefundRequest> findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderById(
            Collection<RefundStatus> statuses, LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<RefundRequest> findTop50ByStatusAndUpdatedAtLessThanEqualOrderById(
            RefundStatus status, LocalDateTime updatedAt);
}
