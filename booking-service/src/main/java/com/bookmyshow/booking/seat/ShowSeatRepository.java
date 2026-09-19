package com.bookmyshow.booking.seat;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ShowSeatRepository extends JpaRepository<ShowSeat, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ShowSeat s where s.id in :ids order by s.id")
    List<ShowSeat> findAllForUpdate(@Param("ids") List<Long> ids);

    @Query("select s from ShowSeat s where s.showId = :showId and (:status is null or s.status = :status)")
    Page<ShowSeat> findByShow(@Param("showId") Long showId, @Param("status") SeatStatus status, Pageable pageable);
}
