package com.bookmyshow.booking.reservation;

import com.bookmyshow.booking.seat.ShowSeat;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "reservation_seats",
        uniqueConstraints = @UniqueConstraint(columnNames = {"reservation_id", "seat_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationSeat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false, updatable = false)
    private Reservation reservation;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false, updatable = false)
    private ShowSeat seat;

    public ReservationSeat(Reservation reservation, ShowSeat seat) {
        this.reservation = reservation;
        this.seat = seat;
    }
}
