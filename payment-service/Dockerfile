# syntax=docker/dockerfile:1.6

# ─────────────────────────────────────────────────────────────
# Stage 1: Extract Spring Boot layers (optimized)
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS builder

WORKDIR /app

# Use explicit JAR (no wildcard) → deterministic + avoids layer explosion
ARG JAR_FILE=target/app.jar
COPY ${JAR_FILE} app.jar

# Extract layers and remove JAR in SAME layer to avoid duplication
RUN java -Djarmode=layertools -jar app.jar extract \
    && rm app.jar

# ─────────────────────────────────────────────────────────────
# Stage 2: Runtime image (minimal & cache-efficient)
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy layers in optimal order for Docker cache reuse
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./

# Expose configurable port
EXPOSE 8080

# Container-aware JVM tuning (balanced for most services)
ENTRYPOINT ["java",
  "-XX:+UseContainerSupport",
  "-XX:MaxRAMPercentage=75.0",
  "-XX:+UseG1GC",
  "-XX:MaxGCPauseMillis=200",
  "-Djava.security.egd=file:/dev/./urandom",
  "org.springframework.boot.loader.launch.JarLauncher"]