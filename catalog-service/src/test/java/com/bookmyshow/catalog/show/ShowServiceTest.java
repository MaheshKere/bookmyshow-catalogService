package com.bookmyshow.catalog.show;

import com.bookmyshow.catalog.exception.*;
import com.bookmyshow.catalog.movie.*;
import com.bookmyshow.catalog.screen.*;
import com.bookmyshow.catalog.theater.*;
import com.bookmyshow.catalog.show.dto.ShowRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShowServiceTest {
    @Mock ShowRepository repository;
    @Mock MovieRepository movieRepository;
    @Mock ScreenRepository screenRepository;
    @Mock TheaterRepository theaterRepository;
    ShowService service;
    Movie movie;
    Screen screen;
    final Instant start = Instant.parse("2026-10-01T10:00:00Z");
    final Instant end = start.plusSeconds(7200);

    @BeforeEach void setUp() {
        service = new ShowService(repository, movieRepository, screenRepository, theaterRepository);
        movie = new Movie("Arrival", "Description", "English", "Drama", 116, LocalDate.now(), true);
        ReflectionTestUtils.setField(movie, "id", 1L);
        var theater = new Theater("Cinema", "Pune", "Road", true);
        ReflectionTestUtils.setField(theater, "id", 2L);
        screen = new Screen("Screen", 100, true, theater);
        ReflectionTestUtils.setField(screen, "id", 3L);
    }

    private ShowRequest request() { return new ShowRequest(1L, 3L, 2L, start, end, true); }

    private void existingParents() {
        when(movieRepository.findById(1L)).thenReturn(Optional.of(movie));
        when(theaterRepository.existsById(2L)).thenReturn(true);
        when(screenRepository.findById(3L)).thenReturn(Optional.of(screen));
    }

    @Test void createsShowWithExistingRelationships() {
        existingParents();
        when(repository.save(any(Show.class))).thenAnswer(call -> call.getArgument(0));
        var response = service.create(request());
        assertThat(response.movieTitle()).isEqualTo("Arrival");
        assertThat(response.screenId()).isEqualTo(3L);
        assertThat(response.theaterId()).isEqualTo(2L);
        assertThat(response.city()).isEqualTo("Pune");
        assertThat(response.startTime()).isEqualTo(start);
        assertThat(response.endTime()).isEqualTo(end);
        verify(repository).save(argThat(show -> show.getMovie() == movie && show.getScreen() == screen));
    }

    @Test void rejectsEqualAndReversedTimesBeforeLoadingParents() {
        for (Instant invalidEnd : List.of(start, start.minusSeconds(1))) {
            assertThatThrownBy(() -> service.create(new ShowRequest(1L, 3L, 2L, start, invalidEnd, true)))
                    .isInstanceOf(BusinessValidationException.class);
        }
        verifyNoInteractions(repository, movieRepository, screenRepository, theaterRepository);
    }

    @Test void rejectsWrongTheater() {
        existingParents();
        ReflectionTestUtils.setField(screen.getTheater(), "id", 99L);
        assertThatThrownBy(() -> service.create(request())).isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("expected Theater");
        verify(repository, never()).save(any());
    }

    @Test void missingMovieIsNotFound() {
        assertThatThrownBy(() -> service.create(request())).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Movie");
        verifyNoInteractions(repository, screenRepository, theaterRepository);
    }

    @Test void missingTheaterIsNotFound() {
        when(movieRepository.findById(1L)).thenReturn(Optional.of(movie));
        assertThatThrownBy(() -> service.create(request())).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Theater");
        verifyNoInteractions(repository, screenRepository);
    }

    @Test void missingScreenIsNotFound() {
        when(movieRepository.findById(1L)).thenReturn(Optional.of(movie));
        when(theaterRepository.existsById(2L)).thenReturn(true);
        assertThatThrownBy(() -> service.create(request())).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Screen");
        verifyNoInteractions(repository);
    }

    @Test void missingShowIsNotFound() {
        assertThatThrownBy(() -> service.getById(9L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void mapsFetchedShow() {
        when(repository.findById(4L)).thenReturn(Optional.of(new Show(movie, screen, start, end, true)));
        assertThat(service.getById(4L).theaterName()).isEqualTo("Cinema");
    }

    @Test void searchesWithHalfOpenUtcDateRangeAndPreservesPage() {
        var pageable = PageRequest.of(0, 1);
        var date = LocalDate.of(2026, 10, 1);
        var from = Instant.parse("2026-10-01T00:00:00Z");
        var until = Instant.parse("2026-10-02T00:00:00Z");
        when(repository.search(1L, 2L, "Pune", from, until, pageable))
                .thenReturn(new PageImpl<>(List.of(new Show(movie, screen, start, end, true)), pageable, 2));
        var result = service.search(1L, 2L, "Pune", date, pageable);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent().get(0).movieId()).isEqualTo(1L);
    }
}
