# Runtime-only image; the jar is built and tested first by `mvn clean verify`.
#   .\mvnw.cmd clean verify
#   docker build -f transfer-service/Dockerfile -t securebank/transfer-service:dev .
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S bank && adduser -S bank -G bank
WORKDIR /app
COPY transfer-service/target/transfer-service-1.0.0-SNAPSHOT.jar app.jar
USER bank
EXPOSE 8083
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s \
  CMD wget -qO- http://localhost:8083/actuator/health/liveness || exit 1
# TRANSFER_DB_USER, TRANSFER_DB_PASSWORD and JWT_SECRET are injected at runtime
# (ECS task definition -> AWS Secrets Manager). Never bake them into the image.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
