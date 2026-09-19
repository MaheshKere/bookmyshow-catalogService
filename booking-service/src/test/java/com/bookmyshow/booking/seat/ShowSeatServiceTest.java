package com.bookmyshow.booking.seat;

import com.bookmyshow.booking.exception.BusinessValidationException;
import com.bookmyshow.booking.seat.dto.InitializeSeatsRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShowSeatServiceTest {
    @Mock ShowSeatRepository repository;
    @Test void initializesAvailableSeats() {
        when(repository.saveAllAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var result = new ShowSeatService(repository).initialize(100L, new InitializeSeatsRequest(List.of("A1", "A2")));
        assertThat(result).extracting("seatNumber").containsExactly("A1", "A2");
        assertThat(result).allSatisfy(seat -> assertThat(seat.status()).isEqualTo(SeatStatus.AVAILABLE));
    }
    @Test void rejectsDuplicateNumbersInRequest() {
        assertThatThrownBy(() -> new ShowSeatService(repository).initialize(100L,
                new InitializeSeatsRequest(List.of("A1", "A1")))).isInstanceOf(BusinessValidationException.class);
        verifyNoInteractions(repository);
    }
    @Test void mapsStatusFilteredPage() {
        var pageable = PageRequest.of(0, 1);
        when(repository.findByShow(100L, SeatStatus.AVAILABLE, pageable))
                .thenReturn(new PageImpl<>(List.of(new ShowSeat(100L, "A1")), pageable, 2));
        var page = new ShowSeatService(repository).getByShow(100L, SeatStatus.AVAILABLE, pageable);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().get(0).seatNumber()).isEqualTo("A1");
    }
}
