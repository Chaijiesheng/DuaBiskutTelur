# Multi-stage build: compile with Maven, run on a slim JRE.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
# curl solely for HEALTHCHECK below — this base image ships neither curl nor
# wget, and a bare TCP probe would only prove the port is bound, not that the
# app is answering.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --create-home appuser
COPY --from=build /app/target/duabiskuttelur-backend-*.jar app.jar
RUN mkdir -p /app/data && chown -R appuser:appuser /app
USER appuser
VOLUME /app/data
EXPOSE 8080

# /api/history answers 401 when unauthenticated, which is a stronger signal than
# a 200 on a static route: it proves the servlet container, Spring Security and
# the session store are all up. start-period covers JVM + Flyway startup.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -sS -o /dev/null -w '%{http_code}' http://localhost:8080/api/history | grep -q '^401$'

ENTRYPOINT ["java", "-jar", "app.jar"]
