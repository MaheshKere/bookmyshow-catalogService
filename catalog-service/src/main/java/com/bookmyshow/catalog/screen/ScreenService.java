package com.bookmyshow.catalog.screen;

import com.bookmyshow.catalog.exception.BusinessValidationException;
import com.bookmyshow.catalog.exception.ResourceNotFoundException;
import com.bookmyshow.catalog.screen.dto.*;
import com.bookmyshow.catalog.theater.TheaterRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ScreenService {
    private final ScreenRepository repository;
    private final TheaterRepository theaterRepository;

    public ScreenService(ScreenRepository repository, TheaterRepository theaterRepository) {
        this.repository = repository;
        this.theaterRepository = theaterRepository;
    }

    @Transactional
    public ScreenResponse create(Long theaterId, ScreenRequest request) {
        if (request.totalSeats() == null || request.totalSeats() <= 0) {
            throw new BusinessValidationException("totalSeats must be positive");
        }
        var theater = theaterRepository.findById(theaterId)
                .orElseThrow(() -> new ResourceNotFoundException("Theater", theaterId));
        return toResponse(repository.save(new Screen(request.name(), request.totalSeats(),
                request.active(), theater)));
    }

    public ScreenResponse getById(Long id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Screen", id)));
    }

    public Page<ScreenResponse> getByTheater(Long theaterId, Pageable pageable) {
        if (!theaterRepository.existsById(theaterId)) {
            throw new ResourceNotFoundException("Theater", theaterId);
        }
        return repository.findByTheaterId(theaterId, pageable).map(this::toResponse);
    }

    private ScreenResponse toResponse(Screen screen) {
        return new ScreenResponse(screen.getId(), screen.getName(), screen.getTotalSeats(),
                screen.isActive(), screen.getTheater().getId(), screen.getCreatedAt(), screen.getUpdatedAt());
    }
}
