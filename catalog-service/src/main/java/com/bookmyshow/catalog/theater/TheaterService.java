package com.bookmyshow.catalog.theater;

import com.bookmyshow.catalog.exception.ResourceNotFoundException;
import com.bookmyshow.catalog.theater.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TheaterService {
    private final TheaterRepository repository;

    public TheaterService(TheaterRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public TheaterResponse create(TheaterRequest request) {
        return toResponse(repository.save(new Theater(request.name(), request.city(),
                request.address(), request.active())));
    }

    public TheaterResponse getById(Long id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Theater", id)));
    }

    public Page<TheaterResponse> getAll(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    private TheaterResponse toResponse(Theater theater) {
        return new TheaterResponse(theater.getId(), theater.getName(), theater.getCity(),
                theater.getAddress(), theater.isActive(), theater.getCreatedAt(), theater.getUpdatedAt());
    }
}
