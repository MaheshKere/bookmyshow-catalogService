package com.bookmyshow.payment;

import com.bookmyshow.payment.payment.*;
import com.bookmyshow.payment.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentController.class)
@Import(SecurityConfiguration.class)
class PaymentSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean PaymentService service;
    final String path = "/api/v1/payments/booking/ref";
    @Test void missingInvalidExpiredAndSpoofedCredentialsAreRejected() throws Exception {
        mvc.perform(get(path).header("X-User-Id", "1").header("X-Role", "ADMIN")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        var expired = TestTokens.token("1", "USER", "bookmyshow-identity", List.of("bookmyshow-api"), Instant.now().minusSeconds(5));
        mvc.perform(get(path).header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void subjectComesFromVerifiedJwt() throws Exception {
        mvc.perform(get(path).header("Authorization", "Bearer " + TestTokens.token("USER")).header("X-User-Id", "999"))
                .andExpect(status().isOk());
        verify(service).get("ref", "1");
    }
    @Test void incorrectIssuerAudienceAndRoleAreRejected() throws Exception {
        for (String token : List.of(
                TestTokens.token("1", "USER", "wrong", List.of("bookmyshow-api"), Instant.now().plusSeconds(60)),
                TestTokens.token("1", "USER", "bookmyshow-identity", List.of("wrong"), Instant.now().plusSeconds(60)),
                TestTokens.token("1", "OTHER", "bookmyshow-identity", List.of("bookmyshow-api"), Instant.now().plusSeconds(60)))) {
            mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service);
    }
}