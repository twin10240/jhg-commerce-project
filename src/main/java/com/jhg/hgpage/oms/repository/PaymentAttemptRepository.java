package com.jhg.hgpage.oms.repository;

import com.jhg.hgpage.oms.domain.PaymentAttempt;
import com.jhg.hgpage.oms.domain.enums.PaymentAttemptStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

    Optional<PaymentAttempt> findByRequestKey(UUID requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PaymentAttempt a join fetch a.payment p join fetch p.order where a.id = :id")
    Optional<PaymentAttempt> findByIdForUpdate(Long id);

    List<PaymentAttempt> findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderById(
            Collection<PaymentAttemptStatus> statuses, LocalDateTime now);
}
