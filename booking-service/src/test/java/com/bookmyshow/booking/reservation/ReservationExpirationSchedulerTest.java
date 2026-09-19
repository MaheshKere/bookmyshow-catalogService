package com.bookmyshow.booking.reservation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class ReservationExpirationSchedulerTest {
    @Test void continuesAfterOneExpirationFails() {
        var service = mock(ReservationService.class);
        when(service.findExpirationCandidates()).thenReturn(List.of(1L, 2L));
        when(service.expire(1L)).thenThrow(new IllegalStateException("simulated conflict"));
        new ReservationExpirationScheduler(service).expireReservations();
        verify(service).expire(1L);
        verify(service).expire(2L);
    }
}
