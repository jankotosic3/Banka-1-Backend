# Notification Service

Event-driven Spring Boot service that consumes employee events from RabbitMQ, renders email content from templates, sends the email through SMTP, and persists the full delivery lifecycle in PostgreSQL.

## What it does

- Consumes RabbitMQ messages from `notification-service-queue`
- Resolves the incoming event from the AMQP routing key, not from the payload
- Renders subject and body from predefined templates plus runtime variables
- Sends email through `JavaMailSender`
- Persists every delivery attempt in `notification_deliveries`
- Retries transient send failures with a scheduler backed by PostgreSQL and an in-memory priority queue

This service does not expose business REST endpoints. It is primarily a RabbitMQ consumer.

## Supported events

| Routing key | Resolved event | Template subject |
| --- | --- | --- |
| `employee.created` | `EMPLOYEE_CREATED` | `Activation Email` |
| `employee.password_reset` | `EMPLOYEE_PASSWORD_RESET` | `Password Reset Email` |
| `employee.account_deactivated` | `EMPLOYEE_ACCOUNT_DEACTIVATED` | `Account Deactivation Email` |

Unsupported routing keys are stored as failed audit records with internal value `UNKNOWN`.

## Message contract

The service consumes a JSON payload compatible with `NotificationRequest`.

The payload does not carry a separate event discriminator. The RabbitMQ routing key is the source of truth for event resolution.

```json
{
  "username": "Ana",
  "userEmail": "employee@example.com",
  "templateVariables": {
    "name": "Ana",
    "activationLink": "https://example.com/activate"
  }
}
```

Accepted aliases:

- `userEmail`: `email`, `recipientEmail`
- `templateVariables`: `params`, `data`, `payload`, `userData`

Notes:

- `userEmail` is required
- `username` is optional
- `username` is automatically reused as `{{username}}` and `{{name}}` if those placeholders are not explicitly provided
- Missing placeholders are left unchanged in the rendered template

## RabbitMQ examples

### `employee.created`

Routing key: `employee.created`

```json
{
  "username": "Ana",
  "userEmail": "employee@example.com",
  "templateVariables": {
    "name": "Ana",
    "activationLink": "https://example.com/activate"
  }
}
```

Placeholder meaning:

- `{{name}}`: employee display name used in the email body
- `{{activationLink}}`: account activation URL

### `employee.password_reset`

Routing key: `employee.password_reset`

```json
{
  "username": "Ana",
  "userEmail": "employee@example.com",
  "templateVariables": {
    "name": "Ana",
    "resetLink": "https://example.com/reset-password"
  }
}
```

Placeholder meaning:

- `{{name}}`: employee display name used in the email body
- `{{resetLink}}`: password reset URL

### `employee.account_deactivated`

Routing key: `employee.account_deactivated`

```json
{
  "username": "Ana",
  "userEmail": "employee@example.com",
  "templateVariables": {
    "name": "Ana"
  }
}
```

Placeholder meaning:

- `{{name}}`: employee display name used in the email body

## Processing flow

1. RabbitMQ delivers a message to `notification-service-queue`.
2. `NotificationMessageListener` receives the payload and AMQP routing key.
3. `NotificationDeliveryService` resolves the event from the routing key.
4. The payload is validated and rendered into a final subject/body using `DefaultNotificationTemplateFactory`.
5. A `notification_deliveries` record is created with status `PENDING`.
6. The service attempts to send the email immediately.
7. On success, the record becomes `SUCCEEDED`.
8. On retryable failure, the record becomes `RETRY_SCHEDULED` and is retried after the configured retry delay.
9. On unsupported payloads, invalid input, or exhausted retries, the record becomes `FAILED`.

## Delivery persistence

Each notification is stored in PostgreSQL table `notification_deliveries` with:

- Internal `deliveryId` UUID
- Recipient email, rendered subject, rendered body
- internal resolved event value in `notificationType` and lifecycle `status`
- `retryCount`, `maxRetries`, `lastError`
- `nextAttemptAt`, `lastAttemptAt`, `sentAt`
- `createdAt`, `updatedAt`

Lifecycle statuses:

- `PENDING`
- `PROCESSING`
- `RETRY_SCHEDULED`
- `SUCCEEDED`
- `FAILED`

## Retry behavior

- Default maximum attempts: `4` total send attempts
- With the default configuration, that means `1` initial attempt plus up to `3` retries
- Default retry delay: `5 seconds`, configurable through `notification.retry.delay-seconds`
- Retry scheduler polling interval: `1000 ms`
- `MailAuthenticationException` is treated as terminal and is not retried
- Retry tasks are reloaded from PostgreSQL on application startup

## Configuration

Main environment variables are defined in `.env.example`.

| Variable | Default | Purpose |
| --- | --- | --- |
| `NOTIFICATION_SERVICE_PORT` | `8006` | HTTP port |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ host |
| `RABBITMQ_PORT` | `5672` | RabbitMQ AMQP port |
| `RABBITMQ_USERNAME` | `guest` | RabbitMQ username |
| `RABBITMQ_PASSWORD` | `guest` | RabbitMQ password |
| `NOTIFICATION_EXCHANGE` | `employee.events` | Topic exchange |
| `NOTIFICATION_QUEUE` | `notification-service-queue` | Consumed queue |
| `NOTIFICATION_ROUTING_KEY` | `employee.#` | Queue binding pattern |
| `POSTGRES_HOST` | `localhost` | PostgreSQL host |
| `POSTGRES_PORT` | `5432` | PostgreSQL port |
| `POSTGRES_DB` | `notification_db` | PostgreSQL database |
| `POSTGRES_USER` | `postgres` | PostgreSQL username |
| `POSTGRES_PASSWORD` | `postgres` | PostgreSQL password |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `update` | Schema strategy |
| `MAIL_HOST` | `smtp.gmail.com` | SMTP host |
| `MAIL_PORT` | `587` | SMTP port |
| `MAIL_USERNAME` | empty | SMTP sender/login |
| `MAIL_PASSWORD` | empty | SMTP password or app password |
| `NOTIFICATION_RETRY_MAX_RETRIES` | `4` | Maximum send attempts |
| `NOTIFICATION_RETRY_DELAY_SECONDS` | `5` | Delay before the next retry attempt |
| `NOTIFICATION_RETRY_SCHEDULER_DELAY_MILLIS` | `1000` | Retry worker poll interval |
| `RABBITMQ_LISTENER_ENABLED` | `true` | Enables the AMQP listener |

## Local development

Prerequisites:

- JDK 23
- Docker Desktop

1. Create a local env file:

```powershell
Copy-Item .env.example .env
```

2. Start PostgreSQL and RabbitMQ:

```powershell
docker compose up -d postgres rabbitmq
```

3. Run the service from the repository root:

```powershell
cd ..
.\gradlew.bat :notification-service:bootRun
```

The service starts on `http://localhost:8006`.

## Running with Docker Compose

From the `notification-service` directory:

```powershell
docker compose up --build
```

This starts PostgreSQL, RabbitMQ, and the notification service container. The Compose build now uses the repository root as context so the local `company-observability-starter` dependency is included correctly.

## Observability and docs

- Health: `http://localhost:8006/actuator/health`
- Readiness: `http://localhost:8006/actuator/health/readiness`
- OpenAPI JSON: `http://localhost:8006/v3/api-docs`
- Swagger UI: `http://localhost:8006/swagger-ui/index.html`

OpenAPI exists mainly to document the consumed event schema. There are no business REST controllers in this service.

## Testing

Run tests from the repository root:

```powershell
.\gradlew.bat :notification-service:test
```

The module includes:

- unit tests for payload validation, template resolution, listener delegation, and retry queue behavior
- integration tests for the end-to-end RabbitMQ to SMTP flow
- retry integration tests for success-after-retry and retry exhaustion scenarios

## Coverage

![img.png](coverage.png)
