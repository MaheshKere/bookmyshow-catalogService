package com.bookmyshow.booking;

import com.bookmyshow.booking.booking.*;
import com.bookmyshow.booking.exception.*;
import com.bookmyshow.booking.reservation.*;
import com.bookmyshow.booking.seat.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.dao.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.context.annotation.Import(com.bookmyshow.booking.security.SecurityConfiguration.class)
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
@WebMvcTest({BookingController.class, ReservationController.class, ShowSeatController.class})
class BookingControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean BookingService bookings;
    @MockitoBean ReservationService reservations;
    @MockitoBean ShowSeatService seats;

    @Test void validatesReservationRequest() throws Exception {
        mvc.perform(post("/api/v1/reservations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"showId\":0,\"seatIds\":[]}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.showId").exists()).andExpect(jsonPath("$.errors.seatIds").exists());
        verifyNoInteractions(reservations);
    }
    @Test void validatesSeatInitializationAndBookingRequest() throws Exception {
        mvc.perform(post("/api/v1/shows/1/seats").contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatNumbers\":[\" \", \"invalid lowercase\"]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.reservationReference").exists());
        verifyNoInteractions(seats, bookings);
    }
    @Test void validatesPathStatusAndPagination() throws Exception {
        mvc.perform(get("/api/v1/shows/0/seats")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/shows/1/seats?status=UNKNOWN")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/shows/1/seats?page=-1&size=101")).andExpect(status().isBadRequest());
        verifyNoInteractions(seats);
    }
    @Test void mapsNotFoundAndBusinessValidation() throws Exception {
        when(bookings.getByReference("missing")).thenThrow(new ResourceNotFoundException("Booking was not found"));
        mvc.perform(get("/api/v1/bookings/missing")).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        when(reservations.reserve(any())).thenThrow(new BusinessValidationException("Seat belongs to a different show"));
        mvc.perform(post("/api/v1/reservations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"showId\":100,\"seatIds\":[1]}")).andExpect(status().isBadRequest());
    }
    @Test void mapsBusinessAndDatabaseConflicts() throws Exception {

        mvc.perform(post("/api/v1/bookings/ref/confirm")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Confirmation is driven by PaymentSucceeded events"));
        when(seats.initialize(anyLong(), any())).thenThrow(new DataIntegrityViolationException("private SQL"));
        mvc.perform(post("/api/v1/shows/100/seats").contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatNumbers\":[\"A1\"]}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The operation conflicts with a database constraint."));
    }
    @Test void mapsOptimisticAndPessimisticConflictsTo409() throws Exception {
        when(bookings.cancel("ref")).thenThrow(new OptimisticLockingFailureException("stale"));
        mvc.perform(post("/api/v1/bookings/ref/cancel")).andExpect(status().isConflict());
        doThrow(new CannotAcquireLockException("timeout")).when(bookings).cancel("ref");
        mvc.perform(post("/api/v1/bookings/ref/cancel")).andExpect(status().isConflict());
    }
}
