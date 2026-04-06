package com.amazon.user.service;

import com.amazon.user.dto.UserDto;
import com.amazon.user.entity.User;
import com.amazon.user.event.UserRegisteredEvent;
import com.amazon.user.exception.DuplicateResourceException;
import com.amazon.user.exception.ResourceNotFoundException;
import com.amazon.user.repository.UserRepository;
import com.amazon.user.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hibernate.internal.util.ExceptionHelper.getRootCause;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, UserDto.UserResponse> redisTemplate; // type-safe
    private final MeterRegistry meterRegistry;

    private static final String USER_CACHE_PREFIX = "user:";
    private static final String USER_REGISTERED_TOPIC = "user.registered";
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final AtomicInteger retryCounter = new AtomicInteger(0);

    // ----------------------- REGISTER -----------------------
    public UserDto.AuthResponse register(UserDto.RegisterRequest request, String fault) {
        validateDuplicate(request);

        User user = buildUserEntity(request);
        try {
            user = userRepository.saveAndFlush(user);
            log.info("User registered successfully: {}", user.getEmail());
        }catch (DataIntegrityViolationException ex) {

            if (isDuplicateConstraint(ex)) {
                throw new DuplicateResourceException("User already exists");
            }

            throw ex;
        }

        cacheUser(user,fault); // type-safe DTO caching
        User finalUser = user;
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publishUserRegisteredEvent(finalUser, fault);
                    }
                }
        ); // synchronous Kafka

        // Fault injection AFTER Kafka + Redis
        if ("fail-after-kafka".equalsIgnoreCase(fault)) {
            log.error("Simulating failure AFTER Kafka + Redis for user: {}", user.getEmail());
            throw new RuntimeException("Simulated failure after Kafka publish");
        }

        incrementRegisteredCounter(user);
        String token = jwtService.generateToken(user);
        return buildAuthResponse(token, user);
    }

    // ----------------------- LOGIN -----------------------
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
        Counter.builder("users.logins").tag("role", user.getRole().name()).register(meterRegistry).increment();

        String token = jwtService.generateToken(user);
        return buildAuthResponse(token, user);
    }

    // ----------------------- GET USER -----------------------
    @Transactional(readOnly = true)
    public UserDto.UserResponse getUserById(UUID id) {
        String cacheKey = USER_CACHE_PREFIX + id;

        UserDto.UserResponse cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("Cache hit for user: {}", id);
            return cached;
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        cacheUser(user,"");
        return mapToUserResponse(user);
    }

    // ----------------------- UPDATE -----------------------
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
        cacheUser(user,"redis-down");
        return mapToUserResponse(user);
    }

    // ----------------------- DELETE -----------------------
    public void deleteUser(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
        user.setStatus(User.UserStatus.INACTIVE);
        userRepository.save(user);
        redisTemplate.delete(USER_CACHE_PREFIX + id);
        log.info("User deactivated: {}", id);
    }

    // ----------------------- CACHE -----------------------
    private void cacheUser(User user,String fault) {
        String cacheKey = USER_CACHE_PREFIX + user.getId();

        try {
            UserDto.UserResponse dto = mapToUserResponse(user);

            // simulate infra failure, not business exception
            if ("redis-down".equalsIgnoreCase(fault)) {
                throw new RuntimeException("Simulated Redis connection failure");
            }

            redisTemplate.opsForValue().set(cacheKey, dto, CACHE_TTL);

        } catch (Exception e) {
            // 🔥 swallow exception
            log.warn("Redis failure - continuing without cache", e);
        }
    }

    // ----------------------- KAFKA -----------------------
    @Retryable(
            value = RuntimeException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000)
    )
    private void publishUserRegisteredEvent(User user, String fault) {
        UserRegisteredEvent event = UserRegisteredEvent.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .username(user.getUsername())
                .registeredAt(LocalDateTime.now())
                .eventType("USER_REGISTERED")
                .build();

        if ("kafka-down".equalsIgnoreCase(fault)) {
            log.error("Simulating Kafka broker down");
            throw new RuntimeException("Simulated Kafka failure");
        }

        if ("fail-once-then-success".equalsIgnoreCase(fault) && retryCounter.getAndIncrement() == 0) {
            log.warn("Simulating transient Kafka failure");
            throw new RuntimeException("Transient Kafka failure");
        }
        // 🔥 3. ACK LOSS simulation (CRITICAL distributed bug)
        if ("kafka-ack-loss".equalsIgnoreCase(fault)) {
            try {
                kafkaTemplate.send(USER_REGISTERED_TOPIC, user.getId().toString(), event).get();

                log.warn("Simulating ACK loss AFTER successful send → producer will retry");

                // simulate producer thinking it failed → retry
                kafkaTemplate.send(USER_REGISTERED_TOPIC, user.getId().toString(), event).get();

                log.warn("Duplicate event likely produced due to retry");

            } catch (Exception e) {
                throw new RuntimeException("Kafka ACK loss simulation failed", e);
            }
            return;
        }

        // 🔥 4. ASYNC SEND (no guarantee)
        if ("kafka-async-no-wait".equalsIgnoreCase(fault)) {
            kafkaTemplate.send(USER_REGISTERED_TOPIC, user.getId().toString(), event);

            log.warn("Kafka async send without waiting for ACK (fire-and-forget)");

            // simulate crash right after send
            throw new RuntimeException("Crash after async send");
        }


        try {
            kafkaTemplate.send(USER_REGISTERED_TOPIC, user.getId().toString(), event).get();
            log.info("Kafka publish SUCCESS for user: {}", user.getId());
        } catch (Exception e) {
            log.error("Kafka publish FAILED for user: {}", user.getId(), e);
            throw new RuntimeException("Kafka publish failed", e);
        }
    }

    // ----------------------- HELPERS -----------------------
    private void validateDuplicate(UserDto.RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email already registered: " + request.getEmail());
        }
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateResourceException("Username already taken: " + request.getUsername());
        }
    }
    private boolean isDuplicateConstraint(DataIntegrityViolationException ex) {
        Throwable root = getRootCause(ex);

        if (root instanceof org.postgresql.util.PSQLException psqlEx) {
            return "23505".equals(psqlEx.getSQLState()); // UNIQUE_VIOLATION
        }
        return false;
    }

    private User buildUserEntity(UserDto.RegisterRequest request) {
        return User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .role(User.Role.CUSTOMER)
                .status(User.UserStatus.ACTIVE)
                .build();
    }

    private void incrementRegisteredCounter(User user) {
        Counter.builder("users.registered")
                .tag("role", user.getRole().name())
                .register(meterRegistry)
                .increment();
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