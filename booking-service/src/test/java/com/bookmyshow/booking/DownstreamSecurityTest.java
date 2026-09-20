package com.bookmyshow.booking;

import com.bookmyshow.booking.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(com.bookmyshow.booking.reservation.ReservationController.class)
@Import(SecurityConfiguration.class)
class DownstreamSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean com.bookmyshow.booking.reservation.ReservationService service;

    @Test void directAccessCannotBypassBearerValidationWithHeaders() throws Exception {
        mvc.perform(post("/api/v1/reservations").header("X-User-Id", "1").header("X-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/reservations").header("Authorization", "Bearer invalid")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void expiredTokenIsRejectedDownstream() throws Exception {
        var expired = TestTokens.token("1", "ADMIN", "bookmyshow-identity",
                List.of("bookmyshow-api"), Instant.now().minusSeconds(5));
        mvc.perform(post("/api/v1/reservations").header("Authorization", "Bearer " + expired)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void rolePolicyIsEnforcedBeforeControllerValidation() throws Exception {
        mvc.perform(post("/api/v1/reservations").header("Authorization", "Bearer " + TestTokens.token("USER"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/reservations").header("Authorization", "Bearer " + TestTokens.token("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
