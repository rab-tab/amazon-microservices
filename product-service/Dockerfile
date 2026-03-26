# syntax=docker/dockerfile:1.6

# ─────────────────────────────────────────────────────────────
# Stage 1: Extract Spring Boot layers (disk-efficient)
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS builder

WORKDIR /app

# Deterministic artifact (override per service if needed)
ARG JAR_FILE=target/app.jar

# Copy only the required JAR
COPY ${JAR_FILE} app.jar

# Extract layers and remove the JAR in the SAME layer
# → avoids duplicate storage (key for macOS Docker VM)
RUN java -Djarmode=layertools -jar app.jar extract \
    && rm app.jar

# ─────────────────────────────────────────────────────────────
# Stage 2: Runtime image (lean + cache-friendly)
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy in cache-optimal order (least → most frequently changed)
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./

EXPOSE 8080

# Container-aware JVM tuning (balanced defaults)
ENTRYPOINT ["java",
  "-XX:+UseContainerSupport",
  "-XX:MaxRAMPercentage=75.0",
  "-XX:+UseG1GC",
  "-XX:MaxGCPauseMillis=200",
  "-Djava.security.egd=file:/dev/./urandom",
  "org.springframework.boot.loader.launch.JarLauncher"]