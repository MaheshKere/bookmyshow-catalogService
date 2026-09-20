package com.bookmyshow.identity.user.dto;

import com.bookmyshow.identity.user.*;
import java.time.Instant;

public record UserResponse(Long id, String email, String firstName, String lastName,
                           Role role, boolean active, Instant createdAt, Instant updatedAt) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                user.getRole(), user.isActive(), user.getCreatedAt(), user.getUpdatedAt());
    }
}
