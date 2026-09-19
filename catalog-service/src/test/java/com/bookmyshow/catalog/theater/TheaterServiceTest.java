package com.bookmyshow.catalog.theater;

import com.bookmyshow.catalog.exception.ResourceNotFoundException;
import com.bookmyshow.catalog.theater.dto.TheaterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TheaterServiceTest {
    @Mock TheaterRepository repository;
    TheaterService service;
    @BeforeEach void setUp() { service = new TheaterService(repository); }

    @Test void createsAndMapsTheater() {
        when(repository.save(any(Theater.class))).thenAnswer(call -> call.getArgument(0));
        var result = service.create(new TheaterRequest("Cinema", "Pune", "Main Road", true));
        assertThat(result.name()).isEqualTo("Cinema");
        assertThat(result.city()).isEqualTo("Pune");
        assertThat(result.address()).isEqualTo("Main Road");
        assertThat(result.active()).isTrue();
        verify(repository).save(any(Theater.class));
    }

    @Test void returnsTheaterAndPageMetadata() {
        var theater = new Theater("Cinema", "Pune", "Main Road", true);
        when(repository.findById(1L)).thenReturn(Optional.of(theater));
        assertThat(service.getById(1L).name()).isEqualTo("Cinema");
        var pageable = PageRequest.of(0, 1);
        when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(theater), pageable, 2));
        var page = service.getAll(pageable);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting("name").containsExactly("Cinema");
    }

    @Test void missingTheaterIsNotFound() {
        assertThatThrownBy(() -> service.getById(9L))
                .isInstanceOf(ResourceNotFoundException.class).hasMessageContaining("Theater");
    }
}
