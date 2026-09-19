package com.bookmyshow.booking.seat.dto;

import java.util.List;

public record ShowSeatPageResponse(List<ShowSeatResponse> content, int page, int size, long totalElements, int totalPages) {
}
