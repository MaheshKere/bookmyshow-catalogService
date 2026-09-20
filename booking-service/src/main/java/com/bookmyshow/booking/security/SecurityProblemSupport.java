package com.bookmyshow.booking.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import java.io.IOException;

final class SecurityProblemSupport {
    private SecurityProblemSupport() {}

    static void write(HttpServletResponse response, ObjectMapper mapper, HttpStatus status) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        mapper.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(status,
                status == HttpStatus.UNAUTHORIZED ? "Authentication is required or the bearer token is invalid."
                        : "You do not have permission to perform this operation."));
    }
}
