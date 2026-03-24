# Card Service

Card Service manages bank debit cards linked to existing account numbers. The current implementation covers the foundation of the microservice: the persistence model, card-number generation rules, CVV handling, and the main card-creation flow.

Implemented features:

- unique card numbers for Visa, MasterCard, DinaCard, and AmEx
- Luhn check-digit generation and validation
- three-digit CVV generation with hashed storage only
- automatic expiration dates set to 5 years after creation
- card status and spending-limit persistence
- unit tests for generation rules and defaults

For shared project setup, git hooks, and infrastructure details, see the [root README](../README.md).

## Current Scope

This module currently implements the business core, not the full API surface.

Implemented now:

- JPA entity and Liquibase schema for cards
- brand-aware card number generation
- Luhn checksum support
- CVV generation plus hashing
- card-creation orchestration
- exception model for business validation failures

Planned later:

- REST endpoints
- account ownership verification against other services
- block, unblock, and deactivate flows
- masked card responses for clients

## Business Model

Each card stores the following fields:

| Field | Meaning | Example |
|---|---|---|
| `cardNumber` | Generated card number including the final Luhn digit | `4111111111111111` |
| `cardType` | Product type, currently fixed to `DEBIT` | `DEBIT` |
| `cardName` | Human-readable product name derived from the brand | `Visa Debit` |
| `creationDate` | Business creation date | `2026-03-23` |
| `expirationDate` | Automatically set to `creationDate + 5 years` | `2031-03-23` |
| `accountNumber` | Linked bank account number | `265000000000001234` |
| `cvv` | Stored only as a hash | `$argon2id$v=19$...` |
| `cardLimit` | Spending limit with monetary precision | `5000.00` |
| `status` | Card lifecycle state | `ACTIVE` |

## Supported Brands

The service currently supports these issuer rules:

| Brand | Prefix rules | Length |
|---|---|---|
| Visa | starts with `4` | 16 |
| MasterCard | starts with `51-55` or `2221-2720` | 16 |
| DinaCard | starts with `9891` | 16 |
| AmEx | starts with `34` or `37` | 15 |

## Card Number Structure

The number-generation flow follows the standard issuer-prefix plus Luhn-check-digit model:

1. choose the brand-specific issuer prefix
2. generate random account-specific digits for the middle section
3. calculate the last digit using the Luhn algorithm
4. check whether the resulting number is already in use
5. retry until a unique number is produced or the retry limit is reached

Example Visa generation:

```text
Prefix:          4
Middle digits:   12345678901234
Payload:         412345678901234
Luhn digit:      9
Final number:    4123456789012349
```

Example AmEx generation:

```text
Prefix:          37
Middle digits:   123456789012
Payload:         37123456789012
Luhn digit:      3
Final number:    371234567890123
```

## CVV Handling

CVV handling follows a stricter security rule than ordinary metadata:

1. the service generates exactly three random digits
2. the plain value is available only once, right after creation
3. the database stores only the Argon2 hash
4. verification is done by hash matching, never by decrypting anything

Example:

```text
Generated plain CVV: 123
Stored CVV hash:     $argon2id$v=19$...
```

## Card Creation Flow

When a new card is created, the service does the following:

1. validates that the account number is not blank
2. validates that the limit is not negative
3. generates a unique brand-compliant card number
4. generates a random three-digit CVV
5. hashes the CVV before persistence
6. sets `cardType` to `DEBIT`
7. derives `cardName`, for example `MasterCard Debit`
8. sets `creationDate` to the current date
9. sets `expirationDate` to five years after `creationDate`
10. sets `status` to `ACTIVE`
11. saves the entity and returns the one-time plain CVV to the caller

Example creation result:

```text
Persisted card:
  cardNumber:     378282246310005
  cardType:       DEBIT
  cardName:       AmEx Debit
  creationDate:   2026-03-23
  expirationDate: 2031-03-23
  accountNumber:  265000000000001234
  cvv:            $argon2id$v=19$...
  cardLimit:      12000.00
  status:         ACTIVE

One-time return value:
  plainCvv:       123
```

## Running Locally

### Hybrid mode

Start only the local dependencies in Docker:

```bash
cd card-service
docker compose up -d postgres_card rabbitmq
```

Then run `CardServiceApplication` from IntelliJ or with Gradle.

### Full stack

```bash
docker compose -f setup/docker-compose.yml up -d --build card-service
```

The service is exposed directly on `http://localhost:8087` and through the API gateway on `http://localhost/api/cards/`.

## Environment Variables

Create a `.env` file in `setup/` or in `card-service/` with values such as:

| Variable | Description | Example |
|---|---|---|
| `CARD_SERVER_PORT` | Port used by the service | `8087` |
| `CARD_DB_HOST` | Card database host | `postgres_card` |
| `CARD_DB_PORT` | Card database port | `5439` |
| `CARD_DB_NAME` | Card database name | `card_db` |
| `CARD_DB_USER` | Card database username | `postgres` |
| `CARD_DB_PASSWORD` | Card database password | `postgres` |
| `JWT_SECRET` | Shared HMAC JWT secret | `my_secret_key` |
| `RABBITMQ_HOST` | RabbitMQ host | `rabbitmq` |
| `RABBITMQ_PORT` | RabbitMQ port | `5672` |
| `RABBITMQ_USERNAME` | RabbitMQ username | `rabbit` |
| `RABBITMQ_PASSWORD` | RabbitMQ password | `rabbit` |
| `NOTIFICATION_QUEUE` | Notification queue name | `notification-service-queue` |
| `NOTIFICATION_EXCHANGE` | Notification exchange name | `employee.events` |
| `NOTIFICATION_ROUTING_KEY` | Notification routing key | `employee.#` |

## Persistence Notes

The service uses:

- PostgreSQL for production persistence
- Liquibase for schema changes
- Hibernate in `validate` mode only

That means table creation must happen through Liquibase migrations, not through automatic Hibernate DDL generation.

## Tests

Run the module tests with:

```bash
./gradlew :card-service:test
```

The JaCoCo HTML report is generated in `card-service/build/reports/jacoco/test/html/index.html`.
