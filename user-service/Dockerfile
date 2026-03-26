# syntax=docker/dockerfile:1.6

# ─────────────────────────────────────────────────────────────
# Stage 1: Extract Spring Boot layers (disk-safe)
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS builder

WORKDIR /app

# Deterministic artifact (override per service if needed)
ARG JAR_FILE=target/last-service.jar

# Copy only the required JAR (no wildcard)
COPY ${JAR_FILE} app.jar

# Extract layers and remove JAR in same layer (prevents duplication)
RUN java -Djarmode=layertools -jar app.jar extract \
    && rm app.jar

# ─────────────────────────────────────────────────────────────
# Stage 2: Runtime image (lean + cache-optimized)
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy layers in optimal order for caching
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./

EXPOSE 8080

# JVM tuned for general microservice workloads
ENTRYPOINT ["java",
  "-XX:+UseContainerSupport",
  "-XX:MaxRAMPercentage=75.0",
  "-XX:+UseG1GC",
  "-XX:MaxGCPauseMillis=200",
  "-Djava.security.egd=file:/dev/./urandom",
  "org.springframework.boot.loader.launch.JarLauncher"]