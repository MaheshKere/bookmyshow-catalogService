package com.bookmyshow.catalog.movie;

import com.bookmyshow.catalog.movie.dto.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@RestController
@RequestMapping("/api/v1/movies")
public class MovieController {
    private final MovieService service;

    public MovieController(MovieService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<MovieResponse> create(@Valid @RequestBody MovieRequest request) {
        MovieResponse response = service.create(request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public MovieResponse getById(@PathVariable @Positive Long id) {
        return service.getById(id);
    }

    @GetMapping
    public MoviePageResponse getAll(@RequestParam(defaultValue = "0") @Min(0) int page,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = service.getAll(PageRequest.of(page, size, Sort.by("id").ascending()));
        return new MoviePageResponse(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @PutMapping("/{id}")
    public MovieResponse update(@PathVariable @Positive Long id,
                                @Valid @RequestBody MovieRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable @Positive Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("fetchByLang/{lang}")
    public List<Movie> getByLanguage(@PathVariable  String lang) {
        return service.findLang(lang);
    }
}
