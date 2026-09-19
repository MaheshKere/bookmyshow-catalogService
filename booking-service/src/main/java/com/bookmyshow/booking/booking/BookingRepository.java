package com.bookmyshow.booking.booking;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    Optional<Booking> findByReservationId(Long reservationId);

    @EntityGraph(attributePaths = "reservation")
    Optional<Booking> findByBookingReference(String reference);

    // Resolve only a scalar before locking the Reservation, avoiding stale managed lifecycle state.
    @Query("select b.reservation.id from Booking b where b.bookingReference = :reference")
    Optional<Long> findReservationId(@Param("reference") String reference);
}
