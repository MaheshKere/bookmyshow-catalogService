package com.bookmyshow.booking.booking;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "bookings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 36)
    private String bookingReference;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false, unique = true)
    private com.bookmyshow.booking.reservation.Reservation reservation;
    @Column(nullable = false)
    private Long showId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    public Booking(com.bookmyshow.booking.reservation.Reservation reservation) {
        this.bookingReference = java.util.UUID.randomUUID().toString();
        this.reservation = reservation;
        this.showId = reservation.getShowId();
        this.status = BookingStatus.PENDING;
    }

    public void confirm() { status = BookingStatus.CONFIRMED; }
    public void cancel() { status = BookingStatus.CANCELLED; }

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
