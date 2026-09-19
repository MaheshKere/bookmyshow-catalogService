package com.bookmyshow.booking.reservation;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ReservationSeatRepository extends JpaRepository<ReservationSeat, Long> {
    @Query("select rs.seat.id from ReservationSeat rs where rs.reservation.id = :reservationId order by rs.seat.id")
    List<Long> findSeatIds(@Param("reservationId") Long reservationId);
}
