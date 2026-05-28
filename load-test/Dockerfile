# Minimal runtime image for load tests. The JAR is built locally with
# `mvn -DskipTests package` before `docker compose up`.
FROM eclipse-temurin:17-jre

WORKDIR /app
COPY ../target/wallet-service-0.1.0-SNAPSHOT.jar /app/wallet-service.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/wallet-service.jar"]
