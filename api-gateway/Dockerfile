# ── Stage 1: Extract Spring Boot layers ──────────────────────────
# WHY? Spring Boot fat JARs contain: dependencies, spring-boot-loader,
# snapshot-dependencies, and application code. By extracting layers,
# Docker caches the slow-changing layers (deps) separately from the
# fast-changing ones (your code). On re-runs, only changed layers
# are re-extracted → startup goes from 60s class-loading to ~5s.
FROM eclipse-temurin:21-jre-alpine AS builder
WORKDIR /app
COPY target/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# ── Stage 2: Runtime image ────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy layers in order of least → most frequently changed
# Docker caches each COPY as a separate layer
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./

EXPOSE 8080

# WHY JVM flags BEFORE -cp?
# With layered JARs we use the Spring Boot loader directly.
# JVM flags must come before the main class, not after -jar.
# -XX:+UseContainerSupport  → respect Docker memory limits
# -XX:MaxRAMPercentage=75.0 → use 75% of container RAM for heap
#                             (adapts automatically to whatever limit is set)
# -Djava.security.egd      → faster SecureRandom (avoids /dev/random blocking)
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseG1GC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "org.springframework.boot.loader.launch.JarLauncher"]
