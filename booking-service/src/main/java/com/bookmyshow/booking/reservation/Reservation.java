package com.bookmyshow.booking.reservation;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 36)
    private String reservationReference;
    @Column(nullable = false)
    private Long showId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;
    @Column(nullable = false)
    private Instant expiresAt;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    public Reservation(Long showId, Instant expiresAt) {
        this.reservationReference = java.util.UUID.randomUUID().toString();
        this.showId = showId;
        this.status = ReservationStatus.ACTIVE;
        this.expiresAt = expiresAt;
    }

    public void requireActive(Instant now) {
        if (status != ReservationStatus.ACTIVE || !expiresAt.isAfter(now)) {
            throw new com.bookmyshow.booking.exception.ConflictException("Reservation is not active or has expired");
        }
    }

    public void confirm() { status = ReservationStatus.CONFIRMED; }
    public void expire() { status = ReservationStatus.EXPIRED; }
    public void cancel() { status = ReservationStatus.CANCELLED; }

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
