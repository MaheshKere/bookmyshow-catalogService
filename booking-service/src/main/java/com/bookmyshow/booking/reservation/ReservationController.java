package com.bookmyshow.booking.reservation;

import com.bookmyshow.booking.reservation.dto.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {
    private final ReservationService service;

    public ReservationController(ReservationService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReservationRequest request) {
        var response = service.reserve(request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{reference}").buildAndExpand(response.reservationReference()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{reference}")
    public ReservationResponse get(@PathVariable @Size(max = 36) String reference) {
        return service.getByReference(reference);
    }

    @PostMapping("/{reference}/cancel")
    public ReservationResponse cancel(@PathVariable @Size(max = 36) String reference) {
        return service.cancel(reference);
    }
}
