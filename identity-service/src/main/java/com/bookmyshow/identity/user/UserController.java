package com.bookmyshow.identity.user;

import com.bookmyshow.identity.user.dto.UserResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService service;
    public UserController(UserService service) { this.service = service; }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return service.currentUser(jwt.getSubject());
    }

    @GetMapping("/admin/status")
    public Map<String, String> adminStatus() { return Map.of("status", "Admin access granted"); }
}
