package com.bookmyshow.catalog.show;

import com.bookmyshow.catalog.exception.BusinessValidationException;
import com.bookmyshow.catalog.exception.ResourceNotFoundException;
import com.bookmyshow.catalog.movie.MovieRepository;
import com.bookmyshow.catalog.screen.ScreenRepository;
import com.bookmyshow.catalog.theater.TheaterRepository;
import com.bookmyshow.catalog.show.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Service
@Transactional(readOnly = true)
public class ShowService {
    // Finite bounds safely represent the API's ISO calendar range in PostgreSQL TIMESTAMPTZ.
    private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("+10000-01-01T00:00:00Z");
    private final ShowRepository repository;
    private final MovieRepository movieRepository;
    private final ScreenRepository screenRepository;
    private final TheaterRepository theaterRepository;

    public ShowService(ShowRepository repository, MovieRepository movieRepository,
                       ScreenRepository screenRepository, TheaterRepository theaterRepository) {
        this.repository = repository;
        this.movieRepository = movieRepository;
        this.screenRepository = screenRepository;
        this.theaterRepository = theaterRepository;
    }

    @Transactional
    public ShowResponse create(ShowRequest request) {
        if (request.startTime() == null || request.endTime() == null
                || !request.startTime().isBefore(request.endTime())) {
            throw new BusinessValidationException("startTime must be before endTime");
        }
        if (request.startTime().isBefore(EARLIEST) || !request.endTime().isBefore(LATEST)) {
            throw new BusinessValidationException("Show times must be within years 0001 through 9999");
        }
        var movie = movieRepository.findById(request.movieId())
                .orElseThrow(() -> new ResourceNotFoundException("Movie", request.movieId()));
        if (!theaterRepository.existsById(request.theaterId())) {
            throw new ResourceNotFoundException("Theater", request.theaterId());
        }
        var screen = screenRepository.findById(request.screenId())
                .orElseThrow(() -> new ResourceNotFoundException("Screen", request.screenId()));
        if (!screen.getTheater().getId().equals(request.theaterId())) {
            throw new BusinessValidationException("Screen does not belong to the expected Theater");
        }
        return toResponse(repository.save(new Show(movie, screen, request.startTime(),
                request.endTime(), request.active())));
    }

    public ShowResponse getById(Long id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Show", id)));
    }

    public Page<ShowResponse> search(Long movieId, Long theaterId, String city,
                                     LocalDate date, Pageable pageable) {
        if (date != null && (date.getYear() < 1 || date.getYear() > 9999)) {
            throw new BusinessValidationException("date must be within years 0001 through 9999");
        }
        Instant from = date == null ? EARLIEST : date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant until = date == null ? LATEST : date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return repository.search(movieId, theaterId, city, from, until, pageable).map(this::toResponse);
    }

    private ShowResponse toResponse(Show show) {
        var movie = show.getMovie();
        var screen = show.getScreen();
        var theater = screen.getTheater();
        return new ShowResponse(show.getId(), movie.getId(), movie.getTitle(),
                screen.getId(), screen.getName(), theater.getId(), theater.getName(), theater.getCity(),
                show.getStartTime(), show.getEndTime(), show.isActive(), show.getCreatedAt(), show.getUpdatedAt());
    }
}
