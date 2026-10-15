# --- Compilación ---
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src/main src/main
RUN ./gradlew --no-daemon bootJar -x test

# --- Ejecución ---
FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 1001 cleantok && mkdir -p /data/almacenamiento && chown -R cleantok /data
WORKDIR /app
COPY --from=build /app/build/libs/cleantok-api.jar app.jar
USER cleantok
ENV ALMACENAMIENTO_RUTA=/data/almacenamiento \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true -Duser.timezone=UTC"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
