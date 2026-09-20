package com.bookmyshow.identity.auth;

import com.bookmyshow.identity.auth.dto.*;
import com.bookmyshow.identity.exception.*;
import com.bookmyshow.identity.security.*;
import com.bookmyshow.identity.user.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock UserRepository repository;
    @Mock AuthenticationManager manager;
    @Mock JwtTokenService tokens;
    final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    AuthService service;
    @BeforeEach void setUp() { service = new AuthService(repository, encoder, manager, tokens); }
    private RegisterRequest request() { return new RegisterRequest("  MAHESH@example.com  ", "Password@123", "Mahesh", "Kere"); }

    @Test void normalizesAndStoresBcryptWithUserRole() {
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var response = service.register(request());
        var captor = ArgumentCaptor.forClass(User.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(response.email()).isEqualTo("mahesh@example.com");
        assertThat(response.role()).isEqualTo(Role.USER);
        assertThat(response.active()).isTrue();
        assertThat(captor.getValue().getPasswordHash()).isNotEqualTo(request().password());
        assertThat(encoder.matches(request().password(), captor.getValue().getPasswordHash())).isTrue();
    }
    @Test void duplicateEmailDoesNotInsert() {
        when(repository.existsByEmail("mahesh@example.com")).thenReturn(true);
        assertThatThrownBy(() -> service.register(request())).isInstanceOf(DuplicateEmailException.class);
        verify(repository, never()).saveAndFlush(any());
    }
    @Test void enforcesBcryptByteLimitForUnicodePasswords() {
        assertThatThrownBy(() -> service.register(new RegisterRequest("a@b.com", String.valueOf((char) 0x00E9).repeat(40), "A", "B")))
                .isInstanceOf(BusinessValidationException.class);
        verifyNoInteractions(repository);
    }
    @Test void loginDelegatesAuthenticationAndTokenGeneration() {
        var principal = new UserPrincipal(new User("a@b.com", encoder.encode("Password@123"), "A", "B"));
        var authenticated = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        when(manager.authenticate(any())).thenReturn(authenticated);
        when(tokens.generate(principal)).thenReturn(new TokenResponse("token", "Bearer", 900));
        assertThat(service.login(new LoginRequest("A@B.COM", "Password@123")).expiresIn()).isEqualTo(900);
        verify(manager).authenticate(argThat(value -> value.getName().equals("a@b.com")));
    }
    @Test void failedLoginNeverGeneratesToken() {
        when(manager.authenticate(any())).thenThrow(new BadCredentialsException("wrong"));
        assertThatThrownBy(() -> service.login(new LoginRequest("a@b.com", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(tokens);
    }
}
