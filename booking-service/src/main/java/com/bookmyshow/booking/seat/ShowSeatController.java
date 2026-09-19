package com.bookmyshow.booking.seat;

import com.bookmyshow.booking.seat.dto.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import java.util.List;

@RestController
@RequestMapping("/api/v1/shows/{showId}/seats")
public class ShowSeatController {
    private final ShowSeatService service;

    public ShowSeatController(ShowSeatService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<List<ShowSeatResponse>> initialize(@PathVariable @Positive Long showId,
                                                            @Valid @RequestBody InitializeSeatsRequest request) {
        var response = service.initialize(showId, request);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest().build().toUri()).body(response);
    }

    @GetMapping
    public ShowSeatPageResponse getByShow(@PathVariable @Positive Long showId,
            @RequestParam(required = false) SeatStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = service.getByShow(showId, status, PageRequest.of(page, size, Sort.by("id")));
        return new ShowSeatPageResponse(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }
}
