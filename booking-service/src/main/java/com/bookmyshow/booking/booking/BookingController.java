package com.bookmyshow.booking.booking;

import com.bookmyshow.booking.booking.dto.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {
    private final BookingService service;

    public BookingController(BookingService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody BookingRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt) {
        var response = service.create(request, jwt.getSubject());
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{reference}").buildAndExpand(response.bookingReference()).toUri();
        // 200 for both first creation and replay: the operation ensures one resource exists.
        return ResponseEntity.ok().location(location).body(response);
    }

    @GetMapping("/{reference}")
    public BookingResponse get(@PathVariable @Size(max = 36) String reference) {
        return service.getByReference(reference);
    }

    @PostMapping("/{reference}/confirm")
    public BookingResponse confirm(@PathVariable @Size(max = 36) String reference) {
        throw new com.bookmyshow.booking.exception.ConflictException("Confirmation is driven by PaymentSucceeded events");
    }

    @PostMapping("/{reference}/cancel")
    public BookingResponse cancel(@PathVariable @Size(max = 36) String reference) {
        return service.cancel(reference);
    }
}
