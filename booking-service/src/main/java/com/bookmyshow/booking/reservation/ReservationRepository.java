package com.bookmyshow.booking.reservation;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    Optional<Reservation> findByReservationReference(String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.reservationReference = :reference")
    Optional<Reservation> findByReferenceForUpdate(@Param("reference") String reference);

    @Query("select r.id from Reservation r where r.status = 'ACTIVE' and r.expiresAt <= :now order by r.expiresAt, r.id")
    List<Long> findExpiredIds(@Param("now") Instant now, Pageable pageable);
}
