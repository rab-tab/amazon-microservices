# ─────────────────────────────────────────────────────────────
# Stage 1: Extract Spring Boot layers (disk-optimized)
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS builder

WORKDIR /app

# Explicit JAR name (MANDATORY for stability)
ARG JAR_FILE=target/notification-service.jar

# Copy only the required artifact
COPY ${JAR_FILE} app.jar

# Extract layers and remove original JAR to avoid duplication
RUN java -Djarmode=layertools -jar app.jar extract \
    && rm app.jar

# ─────────────────────────────────────────────────────────────
# Stage 2: Runtime image (minimal & cache-efficient)
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy layers (ordered for maximum cache reuse)
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./

# Default port (override per service if needed)
EXPOSE 8080

# Container-aware JVM tuning
ENTRYPOINT ["java",
  "-XX:+UseContainerSupport",
  "-XX:MaxRAMPercentage=75.0",
  "-XX:+UseG1GC",
  "-Djava.security.egd=file:/dev/./urandom",
  "org.springframework.boot.loader.launch.JarLauncher"]