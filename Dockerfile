FROM eclipse-temurin:21-jre-alpine
LABEL org.opencontainers.image.title="cycles-server-admin" \
      org.opencontainers.image.description="Cycles administrative API for tenant, budget, and key management" \
      org.opencontainers.image.source="https://github.com/runcycles/cycles-server-admin"

WORKDIR /app
COPY cycles-admin-service/cycles-admin-service-api/target/cycles-admin-service-api-*.jar app.jar

EXPOSE 7979
ENTRYPOINT ["java", "-jar", "app.jar"]
