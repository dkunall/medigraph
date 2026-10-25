# Build first from the repo root:  mvn -pl services/ledger-service -am package -DskipTests
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY target/ledger-service-*.jar app.jar
EXPOSE 8085
HEALTHCHECK --interval=10s --timeout=3s --retries=6 CMD wget -qO- http://localhost:${PORT:-8085}/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
