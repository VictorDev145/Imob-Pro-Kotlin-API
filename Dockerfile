FROM gradle:8.14.3-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon clean bootJar
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 spring && mkdir -p /var/lib/imobpro/uploads && chown -R spring:spring /var/lib/imobpro
COPY --from=build /workspace/build/libs/*.jar app.jar
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
