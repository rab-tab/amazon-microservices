package com.amazon.user.controller;

import com.amazon.user.dto.UserDto;
import com.amazon.user.service.UserService;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserService userService;

    // ─── Auth Endpoints ────────────────────────────────────────────
    @PostMapping("/api/v1/auth/register")
    @Timed(value = "user.register", description = "Time taken to register a user")
    public ResponseEntity<UserDto.AuthResponse> register(@Valid @RequestBody UserDto.RegisterRequest request,
                                                         @RequestHeader(value = "X-Fault", required = false) String fault) {
        log.info("Register request for email: {}", request.getEmail());
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(request,fault));
    }

    @PostMapping("/api/v1/auth/login")
    @Timed(value = "user.login", description = "Time taken to login")
    public ResponseEntity<UserDto.AuthResponse> login(@Valid @RequestBody UserDto.LoginRequest request) {
        log.info("Login request for email: {}", request.getEmail());
        return ResponseEntity.ok(userService.login(request));
    }

    // ─── User Endpoints ────────────────────────────────────────────
    @GetMapping("/api/v1/users/{id}")
    @Timed(value = "user.get", description = "Time taken to get a user")
    public ResponseEntity<UserDto.UserResponse> getUserById(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @PutMapping("/api/v1/users/{id}")
    public ResponseEntity<UserDto.UserResponse> updateProfile(
            @PathVariable UUID id,
            @Valid @RequestBody UserDto.UpdateProfileRequest request,
            @RequestHeader("X-User-Id") String requestingUserId) {

        if (!id.toString().equals(requestingUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(userService.updateProfile(id, request));
    }

    @DeleteMapping("/api/v1/users/{id}")
    public ResponseEntity<Void> deleteUser(
            @PathVariable UUID id,
            @RequestHeader("X-User-Id") String requestingUserId,
            @RequestHeader("X-User-Role") String role) {

        if (!id.toString().equals(requestingUserId) && !"ADMIN".equals(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/users/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("User Service is running");
    }

    @GetMapping("/api/v1/test/secure")
    public String secure() {
        return "SECURED";
    }
}
