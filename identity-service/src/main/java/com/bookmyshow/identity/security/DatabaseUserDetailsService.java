package com.bookmyshow.identity.security;

import com.bookmyshow.identity.user.UserRepository;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;

@Service
@Transactional(readOnly = true)
public class DatabaseUserDetailsService implements UserDetailsService {
    private final UserRepository repository;
    public DatabaseUserDetailsService(UserRepository repository) { this.repository = repository; }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return repository.findByEmail(username.strip().toLowerCase(Locale.ROOT))
                .map(UserPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}
