package com.bookmyshow.identity.user;

import com.bookmyshow.identity.user.dto.UserResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {
    private final UserRepository repository;
    public UserService(UserRepository repository) { this.repository = repository; }

    public UserResponse currentUser(String subject) {
        Long id;
        try { id = Long.valueOf(subject); }
        catch (NumberFormatException exception) { throw new BadCredentialsException("Invalid user"); }
        var user = repository.findById(id).filter(User::isActive)
                .orElseThrow(() -> new BadCredentialsException("Invalid user"));
        return UserResponse.from(user);
    }
}
