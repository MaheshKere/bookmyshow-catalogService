package com.bookmyshow.identity.security;

import com.bookmyshow.identity.user.User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;

public class UserPrincipal extends org.springframework.security.core.userdetails.User {
    private final Long userId;

    public UserPrincipal(User user) {
        super(user.getEmail(), user.getPasswordHash(), user.isActive(), true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        this.userId = user.getId();
    }

    public Long getUserId() { return userId; }
}
