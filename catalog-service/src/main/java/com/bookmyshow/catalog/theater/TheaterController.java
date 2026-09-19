package com.bookmyshow.catalog.theater;

import com.bookmyshow.catalog.theater.dto.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1")
public class TheaterController {
    private final TheaterService service;

    public TheaterController(TheaterService service) {
        this.service = service;
    }

    @PostMapping("/theaters")
    public ResponseEntity<TheaterResponse> create(@Valid @RequestBody TheaterRequest request) {
        var response = service.create(request);
        var location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/theaters/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/theaters/{id}")
    public TheaterResponse getById(@PathVariable @Positive Long id) {
        return service.getById(id);
    }

    @GetMapping("/theaters")
    public TheaterPageResponse getAll(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        var result = service.getAll(pageable);
        return new TheaterPageResponse(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }
}
