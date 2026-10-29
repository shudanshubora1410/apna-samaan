# =============================================================================
# Apna Samaan — Multi-Stage Production Dockerfile (Java 17 LTS)
# Builds the Spring Boot JAR in Stage 1 and packages a minimal non-root JRE
# runtime in Stage 2 alongside the static ES-module `frontend/` bundle.
# =============================================================================

# --- Stage 1: Dependency Cache & Maven Package ---
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /workspace/backend

# Cache Maven dependencies separately from source edits for fast incremental builds
COPY backend/pom.xml ./pom.xml
RUN mvn -B -q dependency:go-offline

COPY backend/src ./src
RUN mvn -B -DskipTests clean package

# --- Stage 2: Minimal Non-Root JRE Runtime ---
FROM eclipse-temurin:17-jre-jammy AS runtime

RUN groupadd --system --gid 10001 apnasamaan \
    && useradd --system --uid 10001 --gid apnasamaan --create-home apnasamaan \
    && mkdir -p /app/frontend /app/uploads/driver-documents \
    && chown -R apnasamaan:apnasamaan /app

WORKDIR /app

COPY --chown=apnasamaan:apnasamaan --from=builder /workspace/backend/target/apna-samaan-backend-1.0.0.jar /app/app.jar
COPY --chown=apnasamaan:apnasamaan frontend/ /app/frontend/

USER apnasamaan

ENV SERVER_PORT=8080 \
    FILE_STORAGE_DIR=/app/uploads/driver-documents \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
    CMD curl -fsS http://127.0.0.1:${PORT:-${SERVER_PORT:-8080}}/api/v1/health >/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
