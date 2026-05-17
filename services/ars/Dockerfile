# ================================================
# Multi-stage build - Optimized for Graviton + Fargate
# ================================================

# --- Build Stage ---
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Copy Maven/Gradle files first for better layer caching
COPY pom.xml . 
# If using Gradle, copy gradle files instead
# COPY gradlew build.gradle settings.gradle ./
# COPY gradle/ gradle/

# Download dependencies (cached if pom.xml doesn't change)
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B || true

# Copy source code and build
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -DskipTests --no-transfer-progress

# --- Runtime Stage (Very small + ARM64 optimized) ---
FROM eclipse-temurin:21-jre-alpine

# Create non-root user for security
RUN addgroup -g 1001 -S spring && \
    adduser -S -u 1001 -G spring spring

WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /build/target/*.jar app.jar

# Change ownership to non-root user
RUN chown -R spring:spring /app

USER spring

EXPOSE 8083

# Optimized JVM flags for container + Java 21
ENV JAVA_OPTS="\
    -XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75 \
    -XX:+UseG1GC \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/tmp"

# Health check (used by ECS)
HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=40s \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8083/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]