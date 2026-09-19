package com.bookmyshow.catalog.screen;

import com.bookmyshow.catalog.exception.*;
import com.bookmyshow.catalog.screen.dto.ScreenRequest;
import com.bookmyshow.catalog.theater.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScreenServiceTest {
    @Mock ScreenRepository repository;
    @Mock TheaterRepository theaterRepository;
    ScreenService service;
    @BeforeEach void setUp() { service = new ScreenService(repository, theaterRepository); }

    @Test void createsScreenWithExistingTheater() {
        var theater = new Theater("Cinema", "Pune", "Road", true);
        ReflectionTestUtils.setField(theater, "id", 1L);
        when(theaterRepository.findById(1L)).thenReturn(Optional.of(theater));
        when(repository.save(any(Screen.class))).thenAnswer(call -> call.getArgument(0));
        var result = service.create(1L, new ScreenRequest("Screen 1", 100, true));
        assertThat(result.theaterId()).isEqualTo(1L);
        assertThat(result.totalSeats()).isEqualTo(100);
        assertThat(result.name()).isEqualTo("Screen 1");
        verify(repository).save(argThat(screen -> screen.getTheater() == theater));
    }

    @ParameterizedTest @ValueSource(ints = {0, -1})
    void rejectsNonPositiveSeats(int seats) {
        assertThatThrownBy(() -> service.create(1L, new ScreenRequest("Screen", seats, true)))
                .isInstanceOf(BusinessValidationException.class);
        verifyNoInteractions(repository, theaterRepository);
    }

    @Test void rejectsMissingTheaterWithoutSaving() {
        assertThatThrownBy(() -> service.create(9L, new ScreenRequest("Screen", 10, true)))
                .isInstanceOf(ResourceNotFoundException.class).hasMessageContaining("Theater");
        verifyNoInteractions(repository);
    }

    @Test void missingScreenAndMissingTheaterListAreNotFound() {
        assertThatThrownBy(() -> service.getById(9L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getByTheater(9L, PageRequest.of(0, 20)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).findByTheaterId(anyLong(), any());
    }

    @Test void mapsScreenAndTheaterPage() {
        var theater = new Theater("Cinema", "Pune", "Road", true);
        ReflectionTestUtils.setField(theater, "id", 1L);
        var screen = new Screen("Screen", 100, false, theater);
        when(repository.findById(2L)).thenReturn(Optional.of(screen));
        assertThat(service.getById(2L).theaterId()).isEqualTo(1L);
        var pageable = PageRequest.of(0, 1);
        when(theaterRepository.existsById(1L)).thenReturn(true);
        when(repository.findByTheaterId(1L, pageable))
                .thenReturn(new PageImpl<>(List.of(screen), pageable, 2));
        var page = service.getByTheater(1L, pageable);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().get(0).active()).isFalse();
    }
}
