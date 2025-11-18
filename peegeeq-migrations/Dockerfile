# PeeGeeQ Migrations Docker Image
#
# This image contains the standalone migration runner JAR and can be used:
# - As a Kubernetes Job
# - As an InitContainer
# - In CI/CD pipelines
# - For local development with Docker Compose
#
# Build:
#   docker build -t peegeeq-migrations:latest -f peegeeq-migrations/Dockerfile .
#
# Run:
#   docker run --rm \
#     -e DB_JDBC_URL=jdbc:postgresql://postgres:5432/peegeeq \
#     -e DB_USER=peegeeq \
#     -e DB_PASSWORD=secret \
#     peegeeq-migrations:latest migrate

# Stage 1: Build the JAR
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy parent POM and migrations module
COPY pom.xml .
COPY peegeeq-migrations/pom.xml peegeeq-migrations/
COPY peegeeq-migrations/src peegeeq-migrations/src

# Build the migrations JAR
RUN mvn clean package -pl peegeeq-migrations -DskipTests

# Stage 2: Runtime image
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="PeeGeeQ Team"
LABEL description="PeeGeeQ Database Migration Runner"
LABEL version="1.0-SNAPSHOT"

# Create non-root user
RUN addgroup -g 1000 peegeeq && \
    adduser -D -u 1000 -G peegeeq peegeeq

WORKDIR /app

# Copy the JAR from builder stage
COPY --from=builder /build/peegeeq-migrations/target/peegeeq-migrations.jar /app/peegeeq-migrations.jar

# Change ownership
RUN chown -R peegeeq:peegeeq /app

# Switch to non-root user
USER peegeeq

# Default command is 'migrate'
ENTRYPOINT ["java", "-jar", "/app/peegeeq-migrations.jar"]
CMD ["migrate"]

# Health check (for Kubernetes readiness)
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=1 \
  CMD java -version || exit 1

