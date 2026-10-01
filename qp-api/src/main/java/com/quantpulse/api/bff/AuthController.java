package com.quantpulse.api.bff;

import com.quantpulse.api.security.JwtService;
import com.quantpulse.api.security.UserAccountService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserAccountService accounts;
    private final JwtService jwt;

    public AuthController(UserAccountService accounts, JwtService jwt) {
        this.accounts = accounts;
        this.jwt = jwt;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String token, String tokenType, long expiresIn,
                                String username, List<String> roles) {
    }

    @PostMapping("/login")
    public ResponseEntity<Object> login(@RequestBody LoginRequest req) {
        return accounts.authenticate(req.username(), req.password())
                .<ResponseEntity<Object>>map(a -> ResponseEntity.ok(new LoginResponse(
                        jwt.issue(a.username(), a.roles()), "Bearer",
                        jwt.ttlSeconds(), a.username(), a.roles())))
                // Same message for unknown user and wrong password.
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "invalid credentials")));
    }

    @GetMapping("/me")
    public ResponseEntity<Object> me(Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(Map.of(
                "username", authentication.getName(),
                "authorities", authentication.getAuthorities().stream()
                        .map(Object::toString).toList()));
    }
}
