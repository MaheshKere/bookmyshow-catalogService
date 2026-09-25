package com.bookmyshow.booking.booking;

import com.bookmyshow.booking.booking.dto.BookingRequest;
import com.bookmyshow.booking.exception.*;
import com.bookmyshow.booking.reservation.*;
import com.bookmyshow.booking.seat.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {
    @Mock BookingRepository repository;
    @Mock com.bookmyshow.booking.messaging.EventStore events;
    @Mock ReservationService reservations;
    BookingService service;
    Reservation reservation;
    Booking booking;
    final Instant now = Instant.parse("2030-01-01T10:00:00Z");
    @BeforeEach void setUp() {
        service = new BookingService(repository, reservations, Clock.fixed(now, ZoneOffset.UTC), events);
        reservation = new Reservation(100L, now.plusSeconds(300));
        ReflectionTestUtils.setField(reservation, "id", 1L);
        booking = new Booking(reservation);
    }
    private void existingBooking() {
        when(repository.findReservationId("ref")).thenReturn(Optional.of(1L));
        when(reservations.lockById(1L)).thenReturn(reservation);
        when(repository.findByBookingReference("ref")).thenReturn(Optional.of(booking));
    }

    @Test void createsPendingBooking() {
        when(reservations.lockByReference("res")).thenReturn(reservation);
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(reservations.lockHeldSeats(reservation)).thenReturn(List.of(new ShowSeat(100L, "A1")));
        var response = service.create(new BookingRequest("res"), "1");
        assertThat(response.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(response.reservationId()).isEqualTo(1L);
        verify(repository).save(any());
    }

    @Test void duplicateRequestReturnsExistingBookingEvenAfterConfirmation() {
        when(reservations.lockByReference("res")).thenReturn(reservation);
        when(repository.findByReservationId(1L)).thenReturn(Optional.of(booking));
        booking.confirm();
        reservation.confirm();
        var response = service.create(new BookingRequest("res"), "1");
        assertThat(response.bookingReference()).isEqualTo(booking.getBookingReference());
        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        verify(repository, never()).save(any());
    }

    @Test void newBookingRejectsExpiredReservation() {
        ReflectionTestUtils.setField(reservation, "expiresAt", now);
        when(reservations.lockByReference("res")).thenReturn(reservation);
        assertThatThrownBy(() -> service.create(new BookingRequest("res"), "1")).isInstanceOf(ConflictException.class);
        verify(repository, never()).save(any());
    }

    @Test void confirmationChangesAllThreeStatesUsingDirtyChecking() {
        existingBooking();
        var seat = new ShowSeat(100L, "A1");
        seat.hold(reservation);
        when(reservations.lockHeldSeats(reservation)).thenReturn(List.of(seat));
        assertThat(service.confirm("ref").status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.BOOKED);
        verify(repository).flush();
        verify(repository, never()).save(any());
    }

    @Test void confirmationReplayDoesNotChangeSeats() {
        existingBooking();
        booking.confirm();
        reservation.confirm();
        assertThat(service.confirm("ref").status()).isEqualTo(BookingStatus.CONFIRMED);
        verify(reservations, never()).lockHeldSeats(any());
    }

    @Test void cannotConfirmExpiredOrCancelledBooking() {
        existingBooking();
        ReflectionTestUtils.setField(reservation, "expiresAt", now.minusSeconds(1));
        assertThatThrownBy(() -> service.confirm("ref")).isInstanceOf(ConflictException.class);
        booking.cancel();
        assertThatThrownBy(() -> service.confirm("ref")).isInstanceOf(ConflictException.class);
        verify(reservations, never()).lockHeldSeats(any());
    }

    @Test void rechecksExpirationAfterWaitingForSeatLocks() {
        existingBooking();
        when(reservations.lockHeldSeats(reservation)).thenAnswer(call -> {
            ReflectionTestUtils.setField(reservation, "expiresAt", now);
            return List.of(new ShowSeat(100L, "A1"));
        });
        assertThatThrownBy(() -> service.confirm("ref")).isInstanceOf(ConflictException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        verify(repository, never()).flush();
    }

    @Test void rejectsChangedSeatOwnership() {
        existingBooking();
        when(reservations.lockHeldSeats(reservation)).thenThrow(new ConflictException("Ownership mismatch"));
        assertThatThrownBy(() -> service.confirm("ref")).isInstanceOf(ConflictException.class);
        verify(repository, never()).flush();
    }

    @Test void pendingCancellationDelegatesToReservationLifecycle() {
        existingBooking();
        when(reservations.cancel(reservation.getReservationReference())).thenAnswer(call -> {
            booking.cancel();
            reservation.cancel();
            return null;
        });
        assertThat(service.cancel("ref").status()).isEqualTo(BookingStatus.CANCELLED);
        verify(reservations).cancel(reservation.getReservationReference());
    }

    @Test void confirmedCancellationConflictsButCancelledReplaySucceeds() {
        existingBooking();
        booking.confirm();
        assertThatThrownBy(() -> service.cancel("ref")).isInstanceOf(ConflictException.class);
        booking.cancel();
        assertThat(service.cancel("ref").status()).isEqualTo(BookingStatus.CANCELLED);
        verify(reservations, never()).cancel(anyString());
    }

    @Test void missingBookingIsNotFound() {
        assertThatThrownBy(() -> service.getByReference("missing")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.confirm("missing")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.cancel("missing")).isInstanceOf(ResourceNotFoundException.class);
    }
}
