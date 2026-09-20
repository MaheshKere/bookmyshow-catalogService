package com.bookmyshow.identity.security;

import com.bookmyshow.identity.user.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationProviderTest {
    final UserRepository repository = mock(UserRepository.class);
    final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    final AuthenticationManager manager = new AuthenticationConfiguration()
            .authenticationManager(new DatabaseUserDetailsService(repository), encoder);
    User user;
    @BeforeEach void setUp() {
        user = new User("a@b.com", encoder.encode("Password@123"), "A", "B");
        ReflectionTestUtils.setField(user, "id", 1L);
        when(repository.findByEmail("a@b.com")).thenReturn(Optional.of(user));
    }
    @Test void authenticatesThroughProviderUserDetailsAndPasswordEncoder() {
        var authentication = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(" A@B.COM ", "Password@123"));
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(((UserPrincipal) authentication.getPrincipal()).getUserId()).isEqualTo(1L);
        assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        assertThat(authentication.getCredentials()).isNull();
    }
    @Test void rejectsWrongPassword() {
        assertThatThrownBy(() -> manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("a@b.com", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
    }
    @Test void rejectsInactiveUser() {
        ReflectionTestUtils.setField(user, "active", false);
        assertThatThrownBy(() -> manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("a@b.com", "Password@123")))
                .isInstanceOf(DisabledException.class);
    }
}
