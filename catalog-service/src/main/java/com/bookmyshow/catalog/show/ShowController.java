package com.bookmyshow.catalog.show;

import com.bookmyshow.catalog.show.dto.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1")
public class ShowController {
    private final ShowService service;

    public ShowController(ShowService service) {
        this.service = service;
    }

    @PostMapping("/shows")
    public ResponseEntity<ShowResponse> create(@Valid @RequestBody ShowRequest request) {
        var response = service.create(request);
        var location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/shows/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/shows/{id}")
    public ShowResponse getById(@PathVariable @Positive Long id) {
        return service.getById(id);
    }

    @GetMapping("/shows")
    public ShowPageResponse search(
            @RequestParam(required = false) @Positive Long movieId,
            @RequestParam(required = false) @Positive Long theaterId,
            @RequestParam(required = false) @Size(max = 100) String city,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var pageable = PageRequest.of(page, size, Sort.by("startTime", "id").ascending());
        var result = service.search(movieId, theaterId, city, date, pageable);
        return new ShowPageResponse(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }
}
