package com.bookmyshow.booking.reservation;

import com.bookmyshow.booking.booking.BookingRepository;
import com.bookmyshow.booking.booking.BookingStatus;
import com.bookmyshow.booking.exception.*;
import com.bookmyshow.booking.reservation.dto.*;
import com.bookmyshow.booking.seat.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ReservationService {
    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private final ReservationRepository repository;
    private final ShowSeatRepository seatRepository;
    private final ReservationSeatRepository membershipRepository;
    private final BookingRepository bookingRepository;
    private final Clock clock;

    public ReservationService(ReservationRepository repository, ShowSeatRepository seatRepository,
                              ReservationSeatRepository membershipRepository, BookingRepository bookingRepository,
                              Clock clock) {
        this.repository = repository;
        this.seatRepository = seatRepository;
        this.membershipRepository = membershipRepository;
        this.bookingRepository = bookingRepository;
        this.clock = clock;
    }

    @Transactional
    public ReservationResponse reserve(ReservationRequest request) {
        if (request.seatIds() == null || request.seatIds().isEmpty()
                || new HashSet<>(request.seatIds()).size() != request.seatIds().size()) {
            throw new BusinessValidationException("seatIds must be nonempty and distinct");
        }
        var seats = seatRepository.findAllForUpdate(request.seatIds());
        if (seats.size() != request.seatIds().size()) {
            throw new ResourceNotFoundException("One or more ShowSeats were not found");
        }
        for (var seat : seats) {
            if (!seat.getShowId().equals(request.showId())) {
                throw new BusinessValidationException("Seat " + seat.getId() + " belongs to a different show");
            }
            if (seat.getStatus() != SeatStatus.AVAILABLE) {
                throw new ConflictException("Seat " + seat.getId() + " is " + seat.getStatus());
            }
        }
        // Start the hold after locks have been acquired, not before a possible lock wait.
        var reservation = repository.save(new Reservation(request.showId(), clock.instant().plus(HOLD_DURATION)));
        for (var seat : seats) {
            membershipRepository.save(new ReservationSeat(reservation, seat));
            seat.hold(reservation);
        }
        repository.flush(); // Dirty checking persists seat ownership/status/version; no save on managed seats.
        return toResponse(reservation, seats.stream().map(ShowSeat::getId).toList());
    }

    public ReservationResponse getByReference(String reference) {
        var reservation = repository.findByReservationReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation was not found"));
        return toResponse(reservation, membershipRepository.findSeatIds(reservation.getId()));
    }

    // These helpers participate in BookingService's transaction; they must never acquire
    // and release a lock in a short independent repository transaction.
    @Transactional(propagation = Propagation.MANDATORY)
    public Reservation lockByReference(String reference) {
        return repository.findByReferenceForUpdate(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation was not found"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Reservation lockById(Long id) {
        return repository.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation was not found"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<ShowSeat> lockHeldSeats(Reservation reservation) {
        var ids = membershipRepository.findSeatIds(reservation.getId());
        if (ids.isEmpty()) {
            throw new ConflictException("Reservation has no seat membership");
        }
        var seats = seatRepository.findAllForUpdate(ids);
        if (seats.size() != ids.size()) {
            throw new ConflictException("Reservation seat membership is incomplete");
        }
        for (var seat : seats) {
            if (seat.getStatus() != SeatStatus.HELD || seat.getCurrentReservation() == null
                    || !reservation.getId().equals(seat.getCurrentReservation().getId())
                    || !reservation.getShowId().equals(seat.getShowId())) {
                throw new ConflictException("Seat ownership no longer matches this reservation");
            }
        }
        return seats;
    }

    public List<Long> findExpirationCandidates() {
        return repository.findExpiredIds(clock.instant(), PageRequest.of(0, 100));
    }

    @Transactional
    public boolean expire(Long id) {
        var reservation = lockById(id);
        if (reservation.getStatus() != ReservationStatus.ACTIVE || reservation.getExpiresAt().isAfter(clock.instant())) {
            return false;
        }
        var seats = lockHeldSeats(reservation);
        seats.forEach(ShowSeat::release);
        reservation.expire();
        cancelPendingBooking(reservation.getId());
        return true;
    }

    @Transactional
    public ReservationResponse cancel(String reference) {
        var reservation = lockByReference(reference);
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            return toResponse(reservation, membershipRepository.findSeatIds(reservation.getId()));
        }
        if (reservation.getStatus() != ReservationStatus.ACTIVE) {
            throw new ConflictException("Only ACTIVE reservations can be cancelled");
        }
        var seats = lockHeldSeats(reservation);
        seats.forEach(ShowSeat::release);
        reservation.cancel();
        cancelPendingBooking(reservation.getId());
        repository.flush();
        return toResponse(reservation, seats.stream().map(ShowSeat::getId).toList());
    }

    private void cancelPendingBooking(Long reservationId) {
        bookingRepository.findByReservationId(reservationId).ifPresent(booking -> {
            if (booking.getStatus() == BookingStatus.PENDING) {
                booking.cancel();
            }
        });
    }

    private ReservationResponse toResponse(Reservation reservation, List<Long> seatIds) {
        return new ReservationResponse(reservation.getId(), reservation.getReservationReference(),
                reservation.getShowId(), reservation.getStatus(), reservation.getExpiresAt(), seatIds,
                reservation.getCreatedAt(), reservation.getUpdatedAt());
    }
}
