package com.bookmyshow.catalog.movie;

import com.bookmyshow.catalog.exception.ResourceNotFoundException;
import com.bookmyshow.catalog.movie.dto.MovieRequest;
import com.bookmyshow.catalog.movie.dto.MovieResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MovieService {
    private final MovieRepository repository;

    public MovieService(MovieRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public MovieResponse create(MovieRequest request) {
        Movie movie = new Movie(request.title(), request.description(), request.language(),
                request.genre(), request.durationMinutes(), request.releaseDate(), request.active());
        return toResponse(repository.save(movie));
    }

    public MovieResponse getById(Long id) {
        return toResponse(findMovie(id));
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
        return toResponse(movie);
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(findMovie(id));
    }

    private Movie findMovie(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Movie", id));
    }

    private MovieResponse toResponse(Movie movie) {
        return new MovieResponse(movie.getId(), movie.getTitle(), movie.getDescription(),
                movie.getLanguage(), movie.getGenre(), movie.getDurationMinutes(),
                movie.getReleaseDate(), movie.isActive(), movie.getCreatedAt(), movie.getUpdatedAt());
    }
}
