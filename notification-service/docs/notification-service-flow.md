# Notification Service Flow

## What this service does
This service listens to RabbitMQ events and sends emails.

It handles only these events:
- `EMPLOYEE_CREATED`
- `EMPLOYEE_PASSWORD_RESET`
- `EMPLOYEE_ACCOUNT_DEACTIVATED`

## Flow (simple)
1. A message comes to queue `notification-service-queue`.
2. `NotificationMessageListener` reads the message.
3. `NotificationService` validates payload data.
4. `NotificationTemplateFactory` returns subject + template based on the routing-key-resolved event.
5. `NotificationService` replaces placeholders like `{{name}}`.
6. Service sends email using `JavaMailSender`.
7. If send fails, service retries up to 3 times with the configured retry delay, which is 5 seconds by default and results in 4 total attempts with the default `notification.retry.max-retries=4`.

## Main classes
- `app.config.RabbitConfig`: exchange, queue, routing key and JSON converter.
- `app.config.SwaggerConfig`: OpenAPI documentation and event schema.
- `app.service.NotificationMessageListener`: RabbitMQ consumer.
- `app.service.NotificationService`: main business logic.
- `app.template.DefaultNotificationTemplateFactory`: subject/template mapping.
- `app.dto.NotificationRequest`: event payload model.

## Sample message
```json
{
  "email": "employee@example.com",
  "userData": {
    "name": "Ana",
    "activationLink": "https://example.com/activate"
  }
}
```
