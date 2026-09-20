package com.bookmyshow.identity.auth;

import com.bookmyshow.identity.auth.dto.*;
import com.bookmyshow.identity.exception.*;
import com.bookmyshow.identity.security.*;
import com.bookmyshow.identity.user.*;
import com.bookmyshow.identity.user.dto.UserResponse;
import org.springframework.security.authentication.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;

@Service
public class AuthService {
    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenService tokens;

    public AuthService(UserRepository repository, PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager, JwtTokenService tokens) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.tokens = tokens;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        validatePasswordBytes(request.password());
        if (repository.existsByEmail(request.email())) {
            throw new DuplicateEmailException();
        }
        var user = new User(request.email(), passwordEncoder.encode(request.password()),
                request.firstName(), request.lastName());
        // Unique email is the final safeguard for concurrent registrations.
        return UserResponse.from(repository.saveAndFlush(user));
    }

    public TokenResponse login(LoginRequest request) {
        validatePasswordBytes(request.password());
        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        return tokens.generate((UserPrincipal) authentication.getPrincipal());
    }

    private void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72 || password.indexOf('\0') >= 0) {
            throw new BusinessValidationException("Password must be at most 72 UTF-8 bytes and contain no NUL character");
        }
    }
}
