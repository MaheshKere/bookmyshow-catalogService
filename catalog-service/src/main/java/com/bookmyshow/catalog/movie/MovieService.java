package com.bookmyshow.catalog.movie;

import com.bookmyshow.catalog.exception.ResourceNotFoundException;
import com.bookmyshow.catalog.movie.dto.MovieRequest;
import com.bookmyshow.catalog.movie.dto.MovieResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bookmyshow.catalog.cache.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class MovieService {
    private final MovieRepository repository;

    private final MovieCache cache;
    private final ApplicationEventPublisher events;

    public MovieService(MovieRepository repository, MovieCache cache, ApplicationEventPublisher events) {
        this.repository = repository; this.cache = cache; this.events = events;
    }

    @Transactional
    public MovieResponse create(MovieRequest request) {
        Movie movie = new Movie(request.title(), request.description(), request.language(),
                request.genre(), request.durationMinutes(), request.releaseDate(), request.active());
        var saved = repository.save(movie);
        events.publishEvent(new MovieChanged(saved.getId()));
        return toResponse(saved);
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public MovieResponse getById(Long id) {
        // A normal cache hit needs no DB transaction. Repository reads have their own transaction.
        // If a caller already owns a transaction, bypass Redis to avoid caching uncommitted state.
        if (TransactionSynchronizationManager.isActualTransactionActive()) return toResponse(findMovie(id));
        return cache.get(id).orElseGet(() -> {
            var movie = toResponse(findMovie(id));
            cache.put(movie);
            return movie;
        });
    }

    public Page<MovieResponse> getAll(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Transactional
    public MovieResponse update(Long id, MovieRequest request) {
        Movie movie = findMovie(id);
        movie.update(request.title(), request.description(), request.language(), request.genre(),
                request.durationMinutes(), request.releaseDate(), request.active());
        // Flush triggers dirty checking and @PreUpdate before mapping the audit timestamp.
        repository.flush();
        events.publishEvent(new MovieChanged(id));
        return toResponse(movie);
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(findMovie(id));
        events.publishEvent(new MovieChanged(id));
    }

    private Movie findMovie(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Movie", id));
    }

    public List<Movie> findLang(String lang) {
        return repository.findByLanguage(lang);
    }
    private MovieResponse toResponse(Movie movie) {
        return new MovieResponse(movie.getId(), movie.getTitle(), movie.getDescription(),
                movie.getLanguage(), movie.getGenre(), movie.getDurationMinutes(),
                movie.getReleaseDate(), movie.isActive(), movie.getCreatedAt(), movie.getUpdatedAt());
    }
}
