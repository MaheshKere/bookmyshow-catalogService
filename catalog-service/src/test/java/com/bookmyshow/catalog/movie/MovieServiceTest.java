package com.bookmyshow.catalog.movie;

import com.bookmyshow.catalog.exception.ResourceNotFoundException;
import com.bookmyshow.catalog.movie.dto.MovieRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MovieServiceTest {
    @Mock MovieRepository repository;
    MovieService service;
    final MovieRequest request = new MovieRequest("Arrival", "A science fiction movie", "English",
            "Science Fiction", 116, LocalDate.of(2016, 11, 11), true);

    @BeforeEach void setUp() { service = new MovieService(repository); }

    @Test void createsMovieFromRequest() {
        when(repository.save(any(Movie.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var response = service.create(request);
        var captor = ArgumentCaptor.forClass(Movie.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo(request.title());
        assertThat(response.title()).isEqualTo(request.title());
        assertThat(response.description()).isEqualTo(request.description());
        assertThat(response.language()).isEqualTo(request.language());
        assertThat(response.genre()).isEqualTo(request.genre());
        assertThat(response.durationMinutes()).isEqualTo(request.durationMinutes());
        assertThat(response.releaseDate()).isEqualTo(request.releaseDate());
        assertThat(response.active()).isTrue();
    }

    @Test void returnsExistingMovie() {
        when(repository.findById(1L)).thenReturn(Optional.of(movie()));
        assertThat(service.getById(1L).title()).isEqualTo("Arrival");
    }

    @Test void missingMovieThrowsNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(99L)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Movie with id 99 was not found");
    }

    @Test void mapsPageAndPreservesMetadata() {
        var pageable = PageRequest.of(0, 2, Sort.by("id"));
        when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(movie()), pageable, 1));
        var result = service.getAll(pageable);
        assertThat(result.getContent()).extracting("title").containsExactly("Arrival");
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(repository).findAll(pageable);
    }

    @Test void updatesManagedMovieAndFlushes() {
        var movie = movie();
        when(repository.findById(1L)).thenReturn(Optional.of(movie));
        var replacement = new MovieRequest("Updated", "New description", "Hindi", "Drama",
                120, LocalDate.of(2027, 1, 1), false);
        var response = service.update(1L, replacement);
        assertThat(movie.getTitle()).isEqualTo("Updated");
        assertThat(response.durationMinutes()).isEqualTo(120);
        assertThat(response.active()).isFalse();
        verify(repository).flush();
        verify(repository, never()).save(any());
    }

    @Test void updateMissingMovieDoesNotFlush() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(99L, request)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).flush();
    }

    @Test void deletesExistingMovie() {
        var movie = movie();
        when(repository.findById(1L)).thenReturn(Optional.of(movie));
        service.delete(1L);
        verify(repository).delete(movie);
    }

    @Test void deleteMissingMovieDoesNotDelete() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(99L)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).delete(any());
    }

    private Movie movie() {
        return new Movie(request.title(), request.description(), request.language(), request.genre(),
                request.durationMinutes(), request.releaseDate(), request.active());
    }
}
