FROM gradle:8.14.3-jdk21-alpine AS builder

WORKDIR /app

# PR_19 C19.X: multi-module Gradle build (kao credit-service) — settings.gradle
# resolves security-lib + company-observability-starter kao subprojects.
COPY . .

RUN gradle :banking-core-service:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl

WORKDIR /app

COPY --from=builder /app/banking-core-service/build/libs/*.jar app.jar

ARG SERVER_PORT=8084
EXPOSE ${SERVER_PORT}

ENTRYPOINT ["java","-jar","app.jar"]
