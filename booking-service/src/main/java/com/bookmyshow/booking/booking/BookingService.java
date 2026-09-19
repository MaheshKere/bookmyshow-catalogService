package com.bookmyshow.booking.booking;

import com.bookmyshow.booking.booking.dto.*;
import com.bookmyshow.booking.exception.*;
import com.bookmyshow.booking.reservation.ReservationService;
import com.bookmyshow.booking.seat.ShowSeat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;

@Service
@Transactional(readOnly = true)
public class BookingService {
    private final BookingRepository repository;
    private final ReservationService reservationService;
    private final Clock clock;

    public BookingService(BookingRepository repository, ReservationService reservationService, Clock clock) {
        this.repository = repository;
        this.reservationService = reservationService;
        this.clock = clock;
    }

    @Transactional
    public BookingResponse create(BookingRequest request) {
        var reservation = reservationService.lockByReference(request.reservationReference());
        var existing = repository.findByReservationId(reservation.getId());
        if (existing.isPresent()) {
            // Replay returns the original resource, including its current terminal state.
            return toResponse(existing.get());
        }
        reservation.requireActive(clock.instant());
        return toResponse(repository.save(new Booking(reservation)));
    }

    public BookingResponse getByReference(String reference) {
        return toResponse(findBooking(reference));
    }

    @Transactional
    public BookingResponse confirm(String reference) {
        var reservation = reservationService.lockById(findReservationId(reference));
        var booking = findBooking(reference);
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            return toResponse(booking);
        }
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new ConflictException("Only PENDING bookings can be confirmed");
        }
        reservation.requireActive(clock.instant());
        var seats = reservationService.lockHeldSeats(reservation);
        reservation.requireActive(clock.instant()); // Recheck deadline after any seat-lock wait.
        seats.forEach(ShowSeat::book);
        reservation.confirm();
        booking.confirm();
        repository.flush();
        return toResponse(booking);
    }

    @Transactional
    public BookingResponse cancel(String reference) {
        var reservation = reservationService.lockById(findReservationId(reference));
        var booking = findBooking(reference);
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return toResponse(booking);
        }
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            throw new ConflictException("Confirmed bookings cannot be cancelled in this phase");
        }
        reservationService.cancel(reservation.getReservationReference());
        repository.flush();
        return toResponse(booking);
    }

    private Long findReservationId(String reference) {
        return repository.findReservationId(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Booking was not found"));
    }

    private Booking findBooking(String reference) {
        return repository.findByBookingReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Booking was not found"));
    }

    private BookingResponse toResponse(Booking booking) {
        return new BookingResponse(booking.getId(), booking.getBookingReference(),
                booking.getReservation().getId(), booking.getReservation().getReservationReference(),
                booking.getShowId(), booking.getStatus(), booking.getCreatedAt(), booking.getUpdatedAt());
    }
}
