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
#     -e DB_SCHEMA=peegeeq \
#     peegeeq-migrations:latest migrate

# The root pom.xml compiles with maven.compiler.release 27 and requires a JDK 27 Maven
# toolchain. No official Maven image ships Temurin 27, so the builder is the Temurin 27 JDK
# image with the Maven installation copied from the official Maven image. Maven itself runs
# on any JDK; compilation uses the toolchain JDK registered below.
FROM maven:3.9.11-eclipse-temurin-25 AS maven

# Stage 1: Build the JAR
FROM eclipse-temurin:27-jdk AS builder

COPY --from=maven /usr/share/maven /usr/share/maven
RUN ln -s /usr/share/maven/bin/mvn /usr/bin/mvn

RUN mkdir -p /root/.m2 && printf '%s\n' \
    '<toolchains>' \
    '  <toolchain>' \
    '    <type>jdk</type>' \
    '    <provides><version>27</version></provides>' \
    "    <configuration><jdkHome>${JAVA_HOME}</jdkHome></configuration>" \
    '  </toolchain>' \
    '</toolchains>' > /root/.m2/toolchains.xml

WORKDIR /build

# Copy the parent POM, the test-support module (a test dependency of the migrations module),
# and the migrations module
COPY pom.xml .
COPY peegeeq-test-support/pom.xml peegeeq-test-support/
COPY peegeeq-test-support/src peegeeq-test-support/src
COPY peegeeq-migrations/pom.xml peegeeq-migrations/
COPY peegeeq-migrations/src peegeeq-migrations/src

# The root POM lists every reactor module, and only these three are in the image. Each POM is
# therefore built by file (-f) instead of as a reactor (-pl), which would fail on the missing
# module directories. -DskipTests still compiles the test sources.
RUN mvn --batch-mode --no-transfer-progress -N install \
 && mvn --batch-mode --no-transfer-progress -f peegeeq-test-support/pom.xml install -DskipTests \
 && mvn --batch-mode --no-transfer-progress -f peegeeq-migrations/pom.xml package -DskipTests

# Stage 2: Runtime image
FROM eclipse-temurin:27-jre-alpine

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

