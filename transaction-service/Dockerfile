FROM gradle:8.14.3-jdk21-alpine AS builder

WORKDIR /app
COPY . .

RUN gradle :transaction-service:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl

WORKDIR /app

COPY --from=builder /app/transaction-service/build/libs/*.jar app.jar

ARG SERVER_PORT=8092
EXPOSE ${SERVER_PORT}

ENTRYPOINT ["java","-jar","app.jar"]
