package com.bookmyshow.identity.auth.dto;

import jakarta.validation.constraints.*;
import java.util.Locale;

public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                           @NotBlank @Size(max = 72) String password) {
    public LoginRequest { email = email == null ? null : email.strip().toLowerCase(Locale.ROOT); }
    @Override public String toString() { return "LoginRequest[credentials redacted]"; }
}
