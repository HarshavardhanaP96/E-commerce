# syntax=docker/dockerfile:1.7
# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src src
# Tests run in CI (they need Docker for Testcontainers); the image build only packages.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/sixpack-backend-*.jar extract --layers --launcher --destination target/extracted

# ---- runtime ----
FROM eclipse-temurin:24-jre-alpine
RUN addgroup -S app && adduser -S -G app app \
 && mkdir -p /app/uploads && chown app:app /app/uploads
WORKDIR /app
ENV TZ=UTC \
    UPLOADS_DIR=/app/uploads \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
# Layer order: least to most frequently changing, for better cache reuse.
COPY --from=build /src/target/extracted/dependencies/ ./
COPY --from=build /src/target/extracted/spring-boot-loader/ ./
COPY --from=build /src/target/extracted/snapshot-dependencies/ ./
COPY --from=build /src/target/extracted/application/ ./
USER app
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health/liveness >/dev/null || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
