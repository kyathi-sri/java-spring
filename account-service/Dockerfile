# Runtime-only image. The jar is built (and tested) first by `mvn clean verify`
# on the developer machine or CI, then copied in. This keeps the image small and means
# the exact artifact that passed the tests is what ships.
#
#   .\mvnw.cmd clean verify
#   docker build -f account-service/Dockerfile -t securebank/account-service:dev .
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S bank && adduser -S bank -G bank
WORKDIR /app
COPY account-service/target/account-service-1.0.0-SNAPSHOT.jar app.jar
USER bank
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s \
  CMD wget -qO- http://localhost:8081/actuator/health/liveness || exit 1
# Secrets (ACCOUNT_DB_USER, ACCOUNT_DB_PASSWORD, JWT_SECRET) are injected at runtime
# (ECS task definition -> AWS Secrets Manager). Never bake them into the image.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
