# ─────────────────────────────────────────────────────────────
# Stage 1: Extract Spring Boot layers (disk-efficient)
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS builder

WORKDIR /app

# Explicit JAR name (MANDATORY for stability)
ARG JAR_FILE=target/notification-service.jar

COPY ${JAR_FILE} app.jar

# Extract layers and immediately delete JAR to avoid duplication
RUN java -Djarmode=layertools -jar app.jar extract \
    && rm app.jar

# ─────────────────────────────────────────────────────────────
# Stage 2: Runtime image (lean + cache-friendly)
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy layers in optimal order for caching
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./

# Notification services are usually I/O bound → keep defaults flexible
EXPOSE 8080

# JVM tuned for container + async workloads (Kafka/email/etc.)
ENTRYPOINT ["java",
  "-XX:+UseContainerSupport",
  "-XX:MaxRAMPercentage=70.0",
  "-XX:+UseG1GC",
  "-XX:MaxGCPauseMillis=200",
  "-Djava.security.egd=file:/dev/./urandom",
  "-Dspring.threads.virtual.enabled=true",
  "org.springframework.boot.loader.launch.JarLauncher"]