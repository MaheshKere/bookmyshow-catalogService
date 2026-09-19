package com.bookmyshow.booking.reservation;

import com.bookmyshow.booking.booking.*;
import com.bookmyshow.booking.exception.*;
import com.bookmyshow.booking.reservation.dto.ReservationRequest;
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
class ReservationServiceTest {
    @Mock ReservationRepository repository;
    @Mock ShowSeatRepository seats;
    @Mock ReservationSeatRepository memberships;
    @Mock BookingRepository bookings;
    ReservationService service;
    final Instant now = Instant.parse("2030-01-01T10:00:00Z");

    @BeforeEach void setUp() {
        service = new ReservationService(repository, seats, memberships, bookings, Clock.fixed(now, ZoneOffset.UTC));
    }
    private ShowSeat seat(long id, long showId) {
        var seat = new ShowSeat(showId, "A" + id);
        ReflectionTestUtils.setField(seat, "id", id);
        return seat;
    }
    private Reservation reservation(Instant expiresAt) {
        var reservation = new Reservation(100L, expiresAt);
        ReflectionTestUtils.setField(reservation, "id", 7L);
        return reservation;
    }
    private void held(Reservation reservation, ShowSeat seat) {
        seat.hold(reservation);
        when(memberships.findSeatIds(7L)).thenReturn(List.of(seat.getId()));
        when(seats.findAllForUpdate(List.of(seat.getId()))).thenReturn(List.of(seat));
    }

    @Test void reservesAllSeatsAndCreatesMembershipWithoutSavingManagedSeats() {
        var first = seat(1, 100);
        var second = seat(2, 100);
        when(seats.findAllForUpdate(List.of(2L, 1L))).thenReturn(List.of(first, second));
        when(repository.save(any())).thenAnswer(call -> {
            var reservation = call.getArgument(0, Reservation.class);
            ReflectionTestUtils.setField(reservation, "id", 7L);
            return reservation;
        });
        var response = service.reserve(new ReservationRequest(100L, List.of(2L, 1L)));
        assertThat(response.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(response.expiresAt()).isEqualTo(now.plusSeconds(300));
        assertThat(response.reservationReference()).hasSize(36);
        assertThat(response.seatIds()).containsExactly(1L, 2L);
        assertThat(first.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(second.getCurrentReservation().getId()).isEqualTo(7L);
        verify(memberships, times(2)).save(any());
        verify(repository).flush();
        verify(seats, never()).save(any());
    }

    @Test void rejectsDuplicateSeatIdsBeforeQuerying() {
        assertThatThrownBy(() -> service.reserve(new ReservationRequest(100L, List.of(1L, 1L))))
                .isInstanceOf(BusinessValidationException.class);
        verifyNoInteractions(seats, repository, memberships);
    }

    @Test void rejectsMissingSeatWithoutPartialReservation() {
        when(seats.findAllForUpdate(List.of(1L, 2L))).thenReturn(List.of(seat(1, 100)));
        assertThatThrownBy(() -> service.reserve(new ReservationRequest(100L, List.of(1L, 2L))))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(repository, memberships);
    }

    @Test void rejectsWrongShowBeforeMutatingAnySeat() {
        var first = seat(1, 100);
        when(seats.findAllForUpdate(List.of(1L, 2L))).thenReturn(List.of(first, seat(2, 101)));
        assertThatThrownBy(() -> service.reserve(new ReservationRequest(100L, List.of(1L, 2L))))
                .isInstanceOf(BusinessValidationException.class);
        assertThat(first.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        verifyNoInteractions(repository, memberships);
    }

    @Test void rejectsHeldAndBookedSeats() {
        var seat = seat(1, 100);
        seat.hold(reservation(now.plusSeconds(300)));
        when(seats.findAllForUpdate(List.of(1L))).thenReturn(List.of(seat));
        assertThatThrownBy(() -> service.reserve(new ReservationRequest(100L, List.of(1L))))
                .isInstanceOf(ConflictException.class);
        seat.book();
        assertThatThrownBy(() -> service.reserve(new ReservationRequest(100L, List.of(1L))))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(repository, memberships);
    }

    @Test void expiresAtDeadlineAndCancelsPendingBooking() {
        var reservation = reservation(now);
        var seat = seat(1, 100);
        var booking = new Booking(reservation);
        when(repository.findForUpdate(7L)).thenReturn(Optional.of(reservation));
        held(reservation, seat);
        when(bookings.findByReservationId(7L)).thenReturn(Optional.of(booking));
        assertThat(service.expire(7L)).isTrue();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.getCurrentReservation()).isNull();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(seats, never()).save(any());
    }

    @Test void skipsUnexpiredAndAlreadyConfirmedReservations() {
        var reservation = reservation(now.plusSeconds(1));
        when(repository.findForUpdate(7L)).thenReturn(Optional.of(reservation));
        assertThat(service.expire(7L)).isFalse();
        reservation.confirm();
        assertThat(service.expire(7L)).isFalse();
        verifyNoInteractions(seats, memberships, bookings);
    }

    @Test void cancellationReleasesOnlyOwnedHeldSeats() {
        var reservation = reservation(now.plusSeconds(300));
        var seat = seat(1, 100);
        when(repository.findByReferenceForUpdate("ref")).thenReturn(Optional.of(reservation));
        held(reservation, seat);
        var response = service.cancel("ref");
        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.getCurrentReservation()).isNull();
        verify(repository).flush();
    }

    @Test void cancellationReplayDoesNotReleaseSeatAgain() {
        var reservation = reservation(now);
        reservation.cancel();
        when(repository.findByReferenceForUpdate("ref")).thenReturn(Optional.of(reservation));
        when(memberships.findSeatIds(7L)).thenReturn(List.of(1L));
        assertThat(service.cancel("ref").status()).isEqualTo(ReservationStatus.CANCELLED);
        verifyNoInteractions(seats, bookings);
    }

    @Test void rejectsConfirmedCancellationAndOwnershipMismatch() {
        var reservation = reservation(now);
        reservation.confirm();
        when(repository.findByReferenceForUpdate("ref")).thenReturn(Optional.of(reservation));
        assertThatThrownBy(() -> service.cancel("ref")).isInstanceOf(ConflictException.class);
        var seat = seat(1, 100);
        var other = new Reservation(100L, now.plusSeconds(300));
        ReflectionTestUtils.setField(other, "id", 8L);
        seat.hold(other);
        when(memberships.findSeatIds(7L)).thenReturn(List.of(1L));
        when(seats.findAllForUpdate(List.of(1L))).thenReturn(List.of(seat));
        assertThatThrownBy(() -> service.lockHeldSeats(reservation)).isInstanceOf(ConflictException.class);
    }

    @Test void missingReservationIsNotFound() {
        assertThatThrownBy(() -> service.getByReference("missing")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.lockByReference("missing")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.expire(9L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
