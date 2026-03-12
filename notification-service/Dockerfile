FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradle gradle
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY settings.gradle ./
COPY company-observability-starter/build.gradle company-observability-starter/build.gradle
COPY company-observability-starter/src company-observability-starter/src
COPY notification-service/build.gradle notification-service/build.gradle
COPY notification-service/src notification-service/src

RUN chmod +x gradlew && ./gradlew --no-daemon :notification-service:clean :notification-service:bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /workspace/notification-service/build/libs/*.jar app.jar

EXPOSE 8006

ENTRYPOINT ["java", "-jar", "app.jar"]
