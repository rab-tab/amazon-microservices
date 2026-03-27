package com.amazon.user.service;

import com.amazon.user.dto.UserDto;
import com.amazon.user.entity.User;
import com.amazon.user.event.UserRegisteredEvent;
import com.amazon.user.exception.DuplicateResourceException;
import com.amazon.user.exception.ResourceNotFoundException;
import com.amazon.user.repository.UserRepository;
import com.amazon.user.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;

    private static final String USER_CACHE_PREFIX = "user:";
    private static final String USER_REGISTERED_TOPIC = "user.registered";
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final AtomicInteger retryCounter = new AtomicInteger(0);

    public UserDto.AuthResponse register(UserDto.RegisterRequest request,String fault) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email already registered: " + request.getEmail());
        }
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateResourceException("Username already taken: " + request.getUsername());
        }

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .role(User.Role.CUSTOMER)
                .status(User.UserStatus.ACTIVE)
                .build();

        user = userRepository.save(user);
        log.info("User registered successfully: {}", user.getEmail());

        // Cache user
        cacheUser(user);

        // Publish Kafka event
        publishUserRegisteredEvent(user,fault);

        // 🔥 Fault injection point
        if ("fail-after-kafka".equalsIgnoreCase(fault)) {
            log.error("Simulating failure AFTER Kafka + Redis for user: {}", user.getEmail());
            throw new RuntimeException("Simulated failure after Kafka publish");
        }

        // Increment counter metric
        Counter.builder("users.registered")
                .tag("role", user.getRole().name())
                .register(meterRegistry)
                .increment();

        String token = jwtService.generateToken(user);
        return buildAuthResponse(token, user);
    }

    public UserDto.AuthResponse login(UserDto.LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        if (user.getStatus() != User.UserStatus.ACTIVE) {
            throw new BadCredentialsException("Account is not active");
        }

        log.info("User logged in: {}", user.getEmail());

        Counter.builder("users.logins")
                .tag("role", user.getRole().name())
                .register(meterRegistry)
                .increment();

        String token = jwtService.generateToken(user);
        return buildAuthResponse(token, user);
    }

    @Transactional(readOnly = true)
    public UserDto.UserResponse getUserById(UUID id) {
        String cacheKey = USER_CACHE_PREFIX + id;

        // Check cache first
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("Cache hit for user: {}", id);
            return mapToUserResponse((User) cached);
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        cacheUser(user);
        return mapToUserResponse(user);
    }

    public UserDto.UserResponse updateProfile(UUID id, UserDto.UpdateProfileRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        if (request.getUsername() != null && !request.getUsername().equals(user.getUsername())) {
            if (userRepository.existsByUsername(request.getUsername())) {
                throw new DuplicateResourceException("Username already taken");
            }
            user.setUsername(request.getUsername());
        }
        if (request.getFirstName() != null) user.setFirstName(request.getFirstName());
        if (request.getLastName() != null) user.setLastName(request.getLastName());
        if (request.getPhone() != null) user.setPhone(request.getPhone());

        user = userRepository.save(user);
        cacheUser(user);
        return mapToUserResponse(user);
    }

    public void deleteUser(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
        user.setStatus(User.UserStatus.INACTIVE);
        userRepository.save(user);
        redisTemplate.delete(USER_CACHE_PREFIX + id);
        log.info("User deactivated: {}", id);
    }

    private void cacheUser(User user) {
        String cacheKey = USER_CACHE_PREFIX + user.getId();
        redisTemplate.opsForValue().set(cacheKey, user, CACHE_TTL);
    }

    private void publishUserRegisteredEvent(User user,String fault) {
        UserRegisteredEvent event = UserRegisteredEvent.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .username(user.getUsername())
                .registeredAt(LocalDateTime.now())
                .eventType("USER_REGISTERED")
                .build();

        // 🔥 Fault injection
        if ("kafka-down".equalsIgnoreCase(fault)) {
            log.error("Simulating Kafka failure");
            throw new RuntimeException("Simulated Kafka failure");
        }
        // 🔥 Transient Kafka failure simulation
        if ("fail-once-then-success".equalsIgnoreCase(fault)) {
            if (retryCounter.getAndIncrement() == 0) {
                log.warn("Simulating transient Kafka failure");
                throw new RuntimeException("Transient Kafka failure");
            }
        }

        kafkaTemplate.send(USER_REGISTERED_TOPIC, user.getId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka publish FAILED for user: {}", user.getId(), ex);
                    } else {
                        log.info("Kafka publish SUCCESS for user: {}", user.getId());
                    }
                });
       // kafkaTemplate.send(USER_REGISTERED_TOPIC, user.getId().toString(), event);
        log.info("Published USER_REGISTERED event for user: {}", user.getId());
    }

    private UserDto.AuthResponse buildAuthResponse(String token, User user) {
        return UserDto.AuthResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresIn(jwtService.getExpirationTime())
                .user(mapToUserResponse(user))
                .build();
    }

    private UserDto.UserResponse mapToUserResponse(User user) {
        return UserDto.UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phone(user.getPhone())
                .role(user.getRole())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
