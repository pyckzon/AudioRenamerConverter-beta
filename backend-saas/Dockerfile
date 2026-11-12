# backend-saas (fase 8): imagen de ejecución del jar de Spring Boot.
# Build: ./gradlew :backend-saas:bootJar
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY backend-saas/build/libs/backend-saas-3.0-SNAPSHOT.jar app.jar
# Credenciales SOLO por entorno (nunca en la imagen):
#   SAAS_DB_URL / SAAS_DB_USER / SAAS_DB_PWD / SAAS_JWT_SECRET / SAAS_PORT
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
