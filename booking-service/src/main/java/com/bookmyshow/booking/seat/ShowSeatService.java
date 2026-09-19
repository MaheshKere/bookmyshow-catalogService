package com.bookmyshow.booking.seat;

import com.bookmyshow.booking.exception.BusinessValidationException;
import com.bookmyshow.booking.seat.dto.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashSet;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ShowSeatService {
    private final ShowSeatRepository repository;

    public ShowSeatService(ShowSeatRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<ShowSeatResponse> initialize(Long showId, InitializeSeatsRequest request) {
        if (new HashSet<>(request.seatNumbers()).size() != request.seatNumbers().size()) {
            throw new BusinessValidationException("seatNumbers must not contain duplicates");
        }
        var seats = request.seatNumbers().stream().map(number -> new ShowSeat(showId, number)).toList();
        // The unique constraint, not a check-then-insert, protects concurrent initialization.
        return repository.saveAllAndFlush(seats).stream().map(this::toResponse).toList();
    }

    public Page<ShowSeatResponse> getByShow(Long showId, SeatStatus status, Pageable pageable) {
        return repository.findByShow(showId, status, pageable).map(this::toResponse);
    }

    private ShowSeatResponse toResponse(ShowSeat seat) {
        return new ShowSeatResponse(seat.getId(), seat.getShowId(), seat.getSeatNumber(),
                seat.getStatus(), seat.getVersion(), seat.getCreatedAt(), seat.getUpdatedAt());
    }
}
