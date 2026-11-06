FROM gradle:8.14.3-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon clean bootJar && find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -exec cp {} /workspace/app.jar \;
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 spring && mkdir -p /var/lib/imobpro/uploads && chown -R spring:spring /var/lib/imobpro
COPY --from=build /workspace/app.jar app.jar
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
