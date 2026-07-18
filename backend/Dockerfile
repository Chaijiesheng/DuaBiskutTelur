# Multi-stage build: compile with Maven, run on a slim JRE.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN useradd --system --create-home appuser
COPY --from=build /app/target/duabiskuttelur-backend-*.jar app.jar
RUN mkdir -p /app/data && chown -R appuser:appuser /app
USER appuser
VOLUME /app/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
