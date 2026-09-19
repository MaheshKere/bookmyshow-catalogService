package com.bookmyshow.catalog.screen;

import com.bookmyshow.catalog.screen.dto.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1")
public class ScreenController {
    private final ScreenService service;

    public ScreenController(ScreenService service) {
        this.service = service;
    }

    @PostMapping("/theaters/{theaterId}/screens")
    public ResponseEntity<ScreenResponse> create(@PathVariable @Positive Long theaterId,
                                                 @Valid @RequestBody ScreenRequest request) {
        var response = service.create(theaterId, request);
        var location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/screens/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/screens/{id}")
    public ScreenResponse getById(@PathVariable @Positive Long id) {
        return service.getById(id);
    }

    @GetMapping("/theaters/{theaterId}/screens")
    public ScreenPageResponse getAll(
            @PathVariable @Positive Long theaterId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        var result = service.getByTheater(theaterId, pageable);
        return new ScreenPageResponse(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }
}
