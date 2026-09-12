FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY backend /workspace/backend
RUN mvn -B -f /workspace/backend/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home --home-dir /app tiempojusto
WORKDIR /app
COPY --from=build /workspace/backend/app/target/tiempojusto-app-1.0.0.jar /app/tiempojusto.jar
USER 10001
EXPOSE 8080 8081
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-Djava.security.egd=file:/dev/urandom","-jar","/app/tiempojusto.jar"]
