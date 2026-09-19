package com.bookmyshow.booking.seat;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "show_seats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShowSeat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long showId;
    @Column(nullable = false, length = 20)
    private String seatNumber;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status;
    @Version
    @Column(nullable = false)
    private Long version;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_reservation_id")
    private com.bookmyshow.booking.reservation.Reservation currentReservation;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    public ShowSeat(Long showId, String seatNumber) {
        this.showId = showId;
        this.seatNumber = seatNumber;
        this.status = SeatStatus.AVAILABLE;
    }

    public void hold(com.bookmyshow.booking.reservation.Reservation reservation) {
        if (status != SeatStatus.AVAILABLE || currentReservation != null) {
            throw new com.bookmyshow.booking.exception.ConflictException("Seat " + id + " is not AVAILABLE");
        }
        status = SeatStatus.HELD;
        currentReservation = reservation;
    }

    public void book() {
        status = SeatStatus.BOOKED;
    }

    public void release() {
        status = SeatStatus.AVAILABLE;
        currentReservation = null;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
