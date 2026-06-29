package com.jhg.hgpage.wms.repository;

import com.jhg.hgpage.wms.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    Optional<Reservation> findByOrderId(Long orderId);
}
