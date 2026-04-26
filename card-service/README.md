# Card Service

Card Service manages bank debit cards linked to existing account numbers. The current implementation covers the foundation of the microservice: the persistence model, card-number generation rules, CVV handling, and the main card-creation flow.

Implemented features:

- unique card numbers for Visa, MasterCard, DinaCard, and AmEx
- Luhn check-digit generation and validation
- three-digit CVV generation with hashed storage only
- automatic expiration dates set to 5 years after creation
- card status and spending-limit persistence
- REST endpoints for automatic, personal, and business card creation
- manual card creation guarded by `verification-service` status checks
- business-card issuance for account owners and authorized persons
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
- REST endpoints for card creation flows
- lifecycle management endpoints for block, unblock, deactivate, and limit updates
- list responses with masked card numbers plus card IDs
- single-card detail lookup by card ID
- account ownership checks against `account-service`
- verification checks against `verification-service`
- exception model for business validation failures

Planned later:

- additional product-specific card features beyond the current debit-card scope

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

## Ownership Model

Card ownership is modeled with two separate identifiers:

- `clientId` stores the owner of the linked account
- `authorizedPersonId` stores the optional business-account person for whom the card was issued

This means the fields are interpreted as follows:

| Scenario | `clientId` | `authorizedPersonId` |
|---|---|---|
| Personal card | personal-account owner | `null` |
| Business card issued to the business owner | business-account owner | `null` |
| Business card issued to an authorized person | business-account owner | authorized person's ID |

Example:

- if a company owner requests a card for themselves, the card keeps the owner's `clientId` and `authorizedPersonId = null`
- if the same owner requests a card for a colleague as an authorized person, the card still keeps the owner's `clientId`, while `authorizedPersonId` points to that colleague

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

## Card Creation Endpoints

`card-service` currently exposes three create flows:

- `POST /auto` for automatic system-driven issuance
- `POST /request` for personal-account card requests
- `POST /request/business` for business-account card requests

All three flows end in the same core card-creation step. Once all preconditions pass, the service:

1. generates a unique brand-compliant card number
2. generates a random three-digit CVV
3. hashes the CVV before persistence
4. sets `cardType` to `DEBIT`
5. derives `cardName`, for example `Visa Debit`
6. sets `creationDate` to the current date
7. sets `expirationDate` to five years after `creationDate`
8. sets `status` to `ACTIVE`
9. saves the card and returns the new `cardId`

Important response rule:

- the full `cardNumber` is returned in the create response
- the plain `CVV` is returned only once, in the create response
- later list endpoints mask the card number
- later card details are fetched by `cardId`

Common create response shape:

```json
{
  "cardId": 42,
  "cardNumber": "4111111111111111",
  "plainCvv": "123",
  "expirationDate": "2031-03-23",
  "cardName": "Visa Debit"
}
```

### Automatic Card Creation

Endpoint:

```text
POST /auto
```

Event trigger used in production flow:

```text
RabbitMQ routing key: card.create
```

Who calls it:

- internal service caller or admin
- intended for the automatic account-created flow
- in the asynchronous flow, `account-service` publishes `card.create` after the account transaction commits
- `card-service` consumes that event and delegates to the same internal automatic creation logic used by `POST /auto`

What the request must contain:

```json
{
  "clientId": 123,
  "accountNumber": "265000000000123456",
  "accountCurrency": "RSD",
  "accountCategory": "PERSONAL",
  "accountType": "CURRENT",
  "accountSubtype": "STANDARD",
  "ownerFirstName": "Pera",
  "ownerLastName": "Peric",
  "ownerEmail": "pera@example.com",
  "ownerUsername": "pperic",
  "accountExpirationDate": "2030-12-31"
}
```

What is actually required for card creation in the current implementation:

- `clientId` must exist
- `accountNumber` must be non-blank

What the service does:

1. validates `clientId` and `accountNumber`
2. picks a random brand from the supported brands
3. applies the configured automatic default card limit
4. creates a personal card for the provided account owner
5. returns the created card immediately

Response:

- HTTP `201 Created`
- body is the common create response shown above

Notes:

- this endpoint does not require `verificationId`
- the extra account-owner fields in the request are currently carried by the internal DTO, but the card creation itself uses `clientId` and `accountNumber`

### Personal Card Request

Endpoint:

```text
POST /request
```

Who calls it:

- authenticated client for their own personal account
- admin can also call it, but the created card still belongs to the actual owner of the account

What the request must contain:

```json
{
  "accountNumber": "265000000000123456",
  "cardBrand": "VISA",
  "cardLimit": 1500.00,
  "verificationId": 77
}
```

What must be true before creation:

- `accountNumber` must be present
- `cardBrand` must be present
- `cardLimit` must be `0` or greater
- the target account must be a personal account
- if the caller is a client, they must own that account
- `verification-service` must return status `VERIFIED` for the provided `verificationId`
- the account owner must have fewer than 2 non-deactivated personal cards on that account

What the service does:

1. loads account context from `account-service`
2. checks ownership in the controller for client callers
3. checks verification status in `verification-service`
4. creates the card for the real owner of the account
5. sends a success notification after the transaction commits

Response:

- HTTP `201 Created`
- body:

```json
{
  "status": "COMPLETED",
  "message": "Card created successfully.",
  "verificationRequestId": null,
  "createdCard": {
    "cardId": 42,
    "cardNumber": "4111111111111111",
    "plainCvv": "123",
    "expirationDate": "2031-03-23",
    "cardName": "Visa Debit"
  }
}
```

### Business Card Request

Endpoint:

```text
POST /request/business
```

Who calls it:

- authenticated business-account owner
- admin can also call it, but the card is still created under the real owner of the business account

What the request must contain:

- `accountNumber`
- `recipientType`
- `cardBrand`
- `cardLimit`
- `verificationId`

If `recipientType` is `OWNER`, the card is issued to the business owner.

Owner example:

```json
{
  "accountNumber": "265000000000999999",
  "recipientType": "OWNER",
  "cardBrand": "DINACARD",
  "cardLimit": 2500.00,
  "verificationId": 88
}
```

If `recipientType` is `AUTHORIZED_PERSON`, the request must identify that person either by:

- `authorizedPersonId`, or
- inline `authorizedPerson` data

Authorized person example:

```json
{
  "accountNumber": "265000000000999999",
  "recipientType": "AUTHORIZED_PERSON",
  "cardBrand": "MASTERCARD",
  "cardLimit": 800.00,
  "verificationId": 99,
  "authorizedPerson": {
    "firstName": "Ana",
    "lastName": "Anic",
    "dateOfBirth": "1994-02-10",
    "gender": "FEMALE",
    "email": "ana@example.com",
    "phone": "0601234567",
    "address": "Adresa 1"
  }
}
```

What must be true before creation:

- `accountNumber` must be present
- `recipientType` must be present
- `cardBrand` must be present
- `cardLimit` must be `0` or greater
- the target account must be a business account
- if the caller is a client, they must own that business account
- `verification-service` must return status `VERIFIED`
- each person may have at most 1 non-deactivated business card on the same account

How the recipient is resolved:

1. if `recipientType = OWNER`, no authorized person is used
2. if `recipientType = AUTHORIZED_PERSON` and `authorizedPersonId` is sent, that person must already exist
3. otherwise the service tries to find an existing authorized person by identity fields
4. if no match exists, a new authorized person is created from the inline payload

What the service does:

1. loads account context from `account-service`
2. checks ownership in the controller for client callers
3. checks verification status in `verification-service`
4. resolves or creates the target authorized person when needed
5. enforces the one-card-per-person business rule
6. creates the card under the business owner, with optional `authorizedPersonId`
7. sends a success notification to the owner and, when applicable, to the authorized person

Response:

- HTTP `201 Created`
- body has the same wrapper shape as `/request`
- `createdCard` contains `cardId`, full `cardNumber`, one-time `plainCvv`, `expirationDate`, and `cardName`

## Card Retrieval Flow

Client and employee retrieval uses two different payload shapes:

- list endpoints return a masked card number together with the card `id`
- creation endpoints return the new `cardId` immediately, together with the real `cardNumber` and one-time `plainCvv`
- the single-card details endpoint uses that `id` and returns the full card number

Current management routes:

- `GET /client/{clientId}` returns the caller's cards with `id`, `maskedCardNumber`, and `accountNumber`
- `GET /account/{accountNumber}` returns the same masked summaries for employee callers
- `GET /id/{cardId}` returns the full card details, including the real `cardNumber`
- `PUT /id/{cardId}/block` blocks the selected card without exposing the PAN in the route
- `PUT /id/{cardId}/unblock` unblocks the selected card without exposing the PAN in the route
- `PUT /id/{cardId}/deactivate` deactivates the selected card without exposing the PAN in the route
- `PUT /id/{cardId}/limit` updates the selected card limit without exposing the PAN in the route
- `GET /internal/account/{accountNumber}` is used by `account-service` and keeps card numbers masked

This means the normal client flow is:

1. load the card list
2. pick a card by `id`
3. call `GET /id/{cardId}` to see the full card details
4. call `PUT /id/{cardId}/block`, `PUT /id/{cardId}/unblock`, `PUT /id/{cardId}/deactivate`, or `PUT /id/{cardId}/limit`

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
| `NOTIFICATION_EXCHANGE` | Notification exchange name | `employee.events` |
| `CARD_AUTO_EXCHANGE` | Exchange name consumed for automatic card creation events | `employee.events` |
| `CARD_AUTO_QUEUE` | Queue consumed by `card-service` for automatic card creation events | `card-service-auto-card-queue` |
| `CARD_AUTO_ROUTING_KEY` | Routing key bound for automatic card creation events | `card.create` |

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

## Card Notification Flow

Card lifecycle changes publish RabbitMQ events consumed by `notification-service`.

Current flow:

1. `blockCard`, `unblockCard`, or `deactivateCard` persists the new status
2. `card-service` resolves the notification recipient from `client-service`
3. `card-service` builds a `CardNotificationDto` payload
4. the event is published only in `afterCommit`, so rollback does not produce a false email
5. `notification-service` consumes routing keys `card.blocked`, `card.unblocked`, and `card.deactivated`

Payload notes:

- `username` is the client display name returned by `client-service`
- `userEmail` is the recipient email returned by `client-service`
- `templateVariables.cardNumber` contains a masked card number
- `templateVariables.accountNumber` contains a masked account number
- `templateVariables.cardName` contains the card display name or fallback `kartica`

## Automatic Card Creation Event Flow

When account creation requests automatic card issuance, the async flow is:

1. `account-service` commits the newly created account
2. after commit, `account-service` publishes `CardEventDto` with routing key `card.create`
3. `card-service` consumes the message from `CARD_AUTO_QUEUE`
4. the listener maps `clientId` and `accountNumber` into `AutoCardCreationRequestDto`
5. `card-service` calls the existing `createAutomaticCard(...)` service flow
6. the new card is persisted with the configured automatic default limit
