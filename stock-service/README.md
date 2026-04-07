# Stock Service

`stock-service` is a Spring Boot microservice that currently provides the initial infrastructure for the stock domain.

The service currently provides:

- a standalone Spring Boot module inside the monorepo
- PostgreSQL connectivity and Liquibase bootstrap migrations
- persisted stock exchange reference data with CSV import support
- persisted futures contract reference data with CSV import support
- persisted FX pair reference data with CSV import support
- persisted stock option reference data linked to underlying stocks
- stock exchange work-time/status endpoints
- JWT authentication through `security-lib`
- observability integration through `company-observability-starter`
- a REST adapter to `exchange-service`
- registration in the central `api-gateway` and `setup/docker-compose.yml`
- actuator health endpoints for liveness and readiness checks

The service uses:

- `Spring Boot 4`
- `PostgreSQL`
- `Liquibase`
- `Spring Security` with JWT resource server support
- `RestClient` for internal communication with other services

## What Is Implemented

- `stock-service` module in the root `settings.gradle`
- dedicated `stock-service/settings.gradle` for standalone builds
- `Dockerfile` and `.dockerignore`
- `application.properties` with database, JWT, and external API configuration
- `SecurityBeans` with a JWT decoder bean
- `RestClientConfig` and configuration properties for `exchange-service`
- `ExchangeServiceClient` adapter for `exchange-service`
- `StockExchange` JPA entity and repository
- `Stock` JPA entity and repository
- `FuturesContract` JPA entity and repository
- `ForexPair` JPA entity and repository
- `StockOption` JPA entity and repository
- startup/admin CSV import flow for stock exchange reference data
- startup CSV import flow for futures contract reference data
- startup CSV import flow for FX pair reference data
- stock exchange listing and market-status API
- stock exchange active-toggle endpoint for testing
- timezone/session-based market-phase calculation
- `HolidayService` extension point with a temporary no-op implementation
- public `GET /info` endpoint
- protected `GET /exchange/info` endpoint
- protected `POST /admin/stock-exchanges/import` endpoint
- Liquibase baseline changelog
- integration with the central `setup/docker-compose.yml`
- gateway route `/stock/`

## Current Domain State

This service is still in the early foundation phase, but it already includes a first persisted stock-domain dataset.

That means:

- the full infrastructure setup exists
- the service can be built and started
- authentication and health checks work
- internal integration with `exchange-service` exists
- stock exchange reference data can be imported from CSV into the database
- stock instrument metadata can now be persisted as a dedicated `stock` entity
- futures contract metadata can now be persisted as a dedicated `futures_contract` entity
- FX pair metadata can now be persisted as a dedicated `forex_pair` entity
- stock option metadata can now be persisted as a dedicated `stock_option` entity linked to `stock`
- stock exchange work-time checks are implemented
- holiday support is intentionally left behind an interface and currently uses a no-op stub

## Package Structure

The most important parts of the service are:

- `config` configuration properties and `RestClient` beans
- `security` JWT configuration for the resource server
- `client` adapter for calling `exchange-service`
- `controller` bootstrap REST endpoints
- `repository` persistence access for stock exchanges, stocks, futures contracts, FX pairs, and stock options
- `domain` persisted stock exchange, stock, futures contract, FX pair, and stock option entities with derived calculations
- `service` CSV import, startup seeding, holiday abstraction, and market-status logic
- `dto` request/response models for internal responses

## API Endpoints

Inside the service itself, the routes are:

- `GET /info`
- `GET /exchange/info`
- `GET /api/stock-exchanges`
- `GET /api/stock-exchanges/{id}/is-open`
- `PUT /api/stock-exchanges/{id}/toggle-active`
- `POST /admin/stock-exchanges/import`
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`

Through the API gateway, the same routes are available under the prefix:

- `GET /stock/info`
- `GET /stock/exchange/info`
- `GET /stock/api/stock-exchanges`
- `GET /stock/api/stock-exchanges/{id}/is-open`
- `PUT /stock/api/stock-exchanges/{id}/toggle-active`
- `POST /stock/admin/stock-exchanges/import`

Note:

- the `/stock` prefix is not added by the service itself
- the prefix is added by `api-gateway`

## Endpoint Details

### `GET /info`

Public endpoint that returns basic service information:

- service name
- status
- gateway prefix
- base URL for `exchange-service`
- base URL for the external stock market data provider
- information about whether the market data API key is configured

Example response:

```json
{
  "service": "stock-service",
  "status": "UP",
  "gatewayPrefix": "/stock",
  "exchangeServiceBaseUrl": "http://exchange-service:8085",
  "marketDataBaseUrl": "https://api.twelvedata.com",
  "marketDataApiKeyConfigured": false
}
```

### `GET /exchange/info`

JWT-protected endpoint that uses the `RestClient` adapter to call `exchange-service` and return its `/info` response.

This endpoint serves as a minimal confirmation that:

- JWT authentication works
- internal service-to-service communication works
- `stock-service` has valid configuration for calling `exchange-service`

### `POST /admin/stock-exchanges/import`

JWT-protected administrative endpoint that re-imports stock exchange reference data from the configured CSV file.

The import is idempotent:

- new MIC codes are inserted
- existing MIC codes are updated when the CSV values change
- unchanged rows are skipped and not persisted again

Example response:

```json
{
  "source": "classpath:seed/exchanges.csv",
  "processedRows": 10,
  "createdCount": 10,
  "updatedCount": 0,
  "unchangedCount": 0
}
```

### `GET /api/stock-exchanges`

Returns the persisted stock exchange catalog sorted by exchange name.

The response includes:

- exchange identity fields
- polity/currency/time-zone metadata
- regular market hours
- optional pre-market and post-market session windows
- current `isActive` toggle state

Authentication:

- requires any valid JWT

### `GET /api/stock-exchanges/{id}/is-open`

Returns the calculated trading status for a single exchange.

The status calculation works like this:

1. current time is converted into the exchange-local timezone from `timeZone`
2. the local date is checked for weekend
3. the local date is passed to `HolidayService`
4. a trading day is defined as `!weekend && !holiday`
5. local time is compared against pre-market, regular, and post-market session windows
6. if `isActive == false`, the exchange is treated as effectively open for testing

Returned status fields include:

- `localDate`
- `localTime`
- `workingDay`
- `holiday`
- `open`
- `regularMarketOpen`
- `testModeBypassEnabled`
- `marketPhase`

`marketPhase` can be:

- `CLOSED`
- `PRE_MARKET`
- `REGULAR_MARKET`
- `POST_MARKET`

Important note about holidays:

- the service already contains the `HolidayService` abstraction
- the current implementation is `NoOpHolidayService`
- that means `holiday` currently resolves to `false` for all dates
- today the runtime behavior therefore depends on timezone, weekend, session windows, and `isActive`
- later the no-op implementation can be replaced by a deterministic DB- or seed-backed calendar without changing the main `is-open` logic

### `PUT /api/stock-exchanges/{id}/toggle-active`

Flips the `isActive` flag of one exchange.

Purpose:

- testing the work-time check without depending on the real trading calendar

Behavior:

- when `isActive == true`, normal work-time/session rules are used
- when `isActive == false`, the exchange is treated as effectively open

Authentication:

- only `ADMIN` and `SUPERVISOR` may call this endpoint

Example response:

```json
{
  "id": 15,
  "exchangeName": "Nasdaq",
  "exchangeMICCode": "XNAS",
  "isActive": false
}
```

## Database

The schema is not generated by Hibernate. Hibernate is set to `validate`, and Liquibase manages migrations.

The stock-service schema currently includes:

- `src/main/resources/db/changelog/001-baseline.sql`
- `src/main/resources/db/changelog/002-create-stock-exchange.sql`
- `src/main/resources/db/changelog/003-create-stock.sql`
- `src/main/resources/db/changelog/004-create-futures-contract.sql`
- `src/main/resources/db/changelog/005-create-forex-pair.sql`
- `src/main/resources/db/changelog/006-create-stock-option.sql`

The `stock_exchange` table stores exchange metadata and trading sessions used by the CSV import flow.

The `stock` table stores:

- unique ticker
- display name
- outstanding share count
- dividend yield

The `futures_contract` table stores:

- unique ticker
- display name
- contract size
- contract unit
- settlement date

The `forex_pair` table stores:

- unique ticker
- base currency
- quote currency
- exchange rate
- liquidity classification

The `stock_option` table stores:

- unique ticker
- foreign key `stock_listing_id` pointing to the underlying `stock` row
- option type
- strike price
- implied volatility
- open interest
- settlement date

Derived values are intentionally kept in the Java domain model instead of being persisted:

- `contractSize` is a constant with value `1`
- `maintenanceMargin(price)` is calculated as `0.5 * price`
- `marketCap(price)` is calculated as `outstandingShares * price`

For `futures_contract`:

- `maintenanceMargin(price)` is calculated as `contractSize * price * 0.10`

For `forex_pair`:

- `contractSize` is a constant with value `1000`
- `nominalValue()` is calculated as `contractSize * exchangeRate`
- `maintenanceMargin()` is calculated as `contractSize * exchangeRate * 0.10`

For `stock_option`:

- `contractSize` is a constant with value `100`
- `maintenanceMargin(stockPrice)` is calculated as `contractSize * 0.50 * stockPrice`

Stock options are not treated as a standalone listing catalog in the current design.
They are attached to one underlying stock and are intended to be shown alongside it.

Liquibase scripts are located in:

- `src/main/resources/db/changelog`

Important rule:

- do not modify migrations that have already been executed
- create a new changelog file for every schema change

## Security

The service uses `security-lib` and runs as a JWT resource server.

Public routes are:

- `/actuator/**`
- `/v3/api-docs/**`
- `/v3/api-docs.yaml`
- `/swagger-ui/**`
- `/swagger-ui.html`
- `/info`

All other routes require a valid JWT token.

Additional role rules:

- `PUT /api/stock-exchanges/{id}/toggle-active` requires `ADMIN` or `SUPERVISOR`
- the listing and `is-open` endpoints only require authentication

The local JWT decoder uses the shared HMAC secret from:

- `JWT_SECRET`

## Configuration

The service reads configuration from:

- `src/main/resources/application.properties`
- `stock-service/.env`
- the root `.env` when started through the central Docker setup

For local development, use `stock-service/.env.example` as the starting point.

Minimal local `.env`:

```env
JWT_SECRET=local_stock_dev_secret_at_least_32_chars
```

If you want to explicitly define the database and internal URLs as well:

```env
STOCK_SERVICE_HOST=0.0.0.0
STOCK_SERVICE_PORT=8090
STOCK_DB_HOST=localhost
STOCK_DB_PORT=5441
STOCK_DB_EX_PORT=5441
STOCK_DB_NAME=stock_db
STOCK_DB_USER=postgres
STOCK_DB_PASSWORD=postgres
JWT_SECRET=local_stock_dev_secret_at_least_32_chars
STOCK_EXCHANGE_SERVICE_URL=http://localhost:8085
STOCK_EXCHANGE_SEED_CSV_LOCATION=classpath:seed/exchanges.csv
STOCK_FUTURES_SEED_CSV_LOCATION=classpath:seed/futures_seed.csv
STOCK_FOREX_SEED_CSV_LOCATION=classpath:seed/forex_pairs_seed.csv
STOCK_MARKET_DATA_BASE_URL=https://api.twelvedata.com
STOCK_MARKET_DATA_API_KEY=replace_with_provider_api_key
```

`STOCK_DB_EX_PORT` is used by `docker compose` for host port mapping. The application itself uses `STOCK_DB_PORT`.

Most important properties:

| Property | Description |
| --- | --- |
| `spring.datasource.url` | PostgreSQL database connection |
| `spring.jpa.hibernate.ddl-auto=validate` | Hibernate does not create tables, it only validates mappings |
| `spring.liquibase.change-log` | location of Liquibase migrations |
| `jwt.secret` | HMAC secret used to validate JWT tokens |
| `stock.exchange-service.base-url` | base URL for the internal `exchange-service` call |
| `stock.exchange-seed.enabled` | enables or disables automatic CSV seeding on startup |
| `stock.exchange-seed.csv-location` | Spring resource location of the stock exchange CSV seed file |
| `stock.futures-seed.enabled` | enables or disables automatic CSV seeding of futures contracts on startup |
| `stock.futures-seed.csv-location` | Spring resource location of the futures contract CSV seed file |
| `stock.forex-seed.enabled` | enables or disables automatic CSV seeding of FX pairs on startup |
| `stock.forex-seed.csv-location` | Spring resource location of the FX pair CSV seed file |
| `stock.market-data.base-url` | base URL for the external stock market data provider |
| `stock.market-data.api-key` | API key for the external market data provider |
| `stock.market-data.default-exchange` | default exchange for future stock queries |

## What Happens on Startup

When the service starts, it:

1. starts the Spring context
2. loads configuration from `application.properties` and `.env`
3. connects to the PostgreSQL database
4. lets Liquibase validate and execute migrations if needed
5. imports stock exchange reference data from the configured CSV file if seeding is enabled
6. imports futures contract reference data from the configured CSV file if seeding is enabled
7. imports FX pair reference data from the configured CSV file if seeding is enabled
8. registers the JWT decoder and security filter chain from `security-lib`
9. registers the `RestClient` bean for `exchange-service`
10. exposes the stock exchange REST endpoints
11. exposes the actuator health endpoints

## CSV Seed Format

The default seed file is:

- `src/main/resources/seed/exchanges.csv`

The importer supports only the current production-oriented format:

- `Exchange Name`
- `Exchange Acronym`
- `Exchange Mic Code`
- `Country`
- `Currency`
- `Time Zone`
- `Open Time`
- `Close Time`

Optional columns:

- `Pre Market Open Time`
- `Pre Market Close Time`
- `Post Market Open Time`
- `Post Market Close Time`
- `Is Active`

Defaulting behavior for the new `exchanges.csv`:

- if pre/post-market columns are missing, those session windows are stored as `null`
- if `Is Active` is missing, the importer defaults it to `true`

That means the new seed file is valid even though it currently contains only regular market hours.

The futures seed file is:

- `src/main/resources/seed/futures_seed.csv`

It uses this format:

- `Ticker`
- `Name`
- `Contract Size`
- `Contract Unit`
- `Settlement Date`

Supported `Contract Unit` values are:

- `Kilogram`
- `Liter`
- `Barrel`

`Settlement Date` must be in ISO format `yyyy-MM-dd`.

The FX pair seed file is:

- `src/main/resources/seed/forex_pairs_seed.csv`

It uses this format:

- `Ticker`
- `Base Currency`
- `Quote Currency`
- `Exchange Rate`
- `Liquidity`

`Base Currency` and `Quote Currency` must be ISO currency codes like `EUR` or `USD`.

`Exchange Rate` must be a positive decimal value.

Supported `Liquidity` values are:

- `HIGH`
- `MEDIUM`
- `LOW`

## Local Development

The most practical development flow is:

- PostgreSQL in Docker
- `stock-service` started from IntelliJ

This gives you:

- debugger support
- local logs
- easier configuration iteration

### 1. Create a local `.env`

Inside the `stock-service` folder, create `.env` based on `.env.example`.

### 2. Start only the stock database

From the project root:

```bash
docker compose -f setup/docker-compose.yml --env-file setup/.env up -d postgres_stock
```

### 3. Start the service from IntelliJ

Run `StockServiceApplication`.

Default local DB access is:

- host: `localhost`
- port: `5441`
- db: `stock_db`

### 4. Verify health

Once the service starts:

```http
GET http://localhost:8090/actuator/health
GET http://localhost:8090/actuator/health/liveness
GET http://localhost:8090/actuator/health/readiness
GET http://localhost:8090/info
```

## Docker Scenarios

### Only the stock database in Docker

```bash
docker compose -f setup/docker-compose.yml --env-file setup/.env up -d postgres_stock
```

### Stock database + stock-service in Docker

```bash
docker compose -f setup/docker-compose.yml --env-file setup/.env up -d --build postgres_stock stock-service
```

### Full central stack with the gateway

```bash
docker compose -f setup/docker-compose.yml --env-file setup/.env up -d --build
```

In that case:

- the service uses `postgres_stock:5432`
- the gateway is exposed via `API_GATEWAY_PORT`
- `stock-service` is available behind the `/stock/` gateway route

## Testing

Run the build/test verification with:

```bash
./gradlew.bat :stock-service:test --no-daemon
```

If you also want the build artifact:

```bash
./gradlew.bat :stock-service:build --no-daemon
```

Note:

- unit tests cover the CSV parsing and idempotent import logic
- unit tests also cover market-phase calculation and controller security for the new stock exchange endpoints
- unit tests now also cover the new `Stock` entity derived calculations and JPA/Liquibase persistence mapping
- unit tests now also cover futures contract CSV import, derived maintenance margin, and JPA/Liquibase persistence mapping
- unit tests now also cover FX pair CSV import, derived nominal value and maintenance margin, and JPA/Liquibase persistence mapping
- unit tests now also cover stock option derived maintenance margin, JPA/Liquibase persistence mapping, and FK enforcement toward `stock`
- the tests do not start the full application stack

## Swagger and OpenAPI

- Swagger UI directly on the service: `/swagger-ui.html`
- Raw OpenAPI directly on the service: `/v3/api-docs`

When accessed through the gateway, the service routes receive the gateway prefix, for example `/stock/info`.

## Integration with Other Services

`stock-service` currently includes a ready-to-use adapter for:

- `exchange-service`

This was intentionally added as the first inter-service integration step, because future stock domain functionality will often depend on:

- exchange rates
- related financial calculations
- internal system data from other banking services

## Common Questions

### Why does the service not have stock tables and stock CRUD endpoints yet?

Because this task was the initial bootstrap of the microservice.

The requested acceptance criteria are covered:

- the service exists as a module
- the service builds successfully
- the service has Docker and Compose integration
- the gateway routes to it
- JWT authentication works
- the health endpoint exists
- the `exchange-service` RestClient adapter exists

Additionally, the service now includes:

- stock exchange listing
- stock exchange `is-open` checks
- market phase detection
- test toggle support for active/inactive exchanges

### Why does `/exchange/info` require JWT while `/info` does not?

Because `/info` is intentionally left public as a bootstrap/info endpoint, while `/exchange/info` is intentionally protected to verify both resource-server security and internal service communication.

### Do holidays affect `is-open` today?

Architecturally yes, operationally not yet.

The `is-open` logic already depends on `HolidayService`, but the current implementation is `NoOpHolidayService`, which always returns `false`.

So today the real behavior is:

- timezone-aware local time
- weekend detection
- regular/pre/post-market session windows
- `isActive` testing override

The abstraction exists so a deterministic holiday source can be added later without refactoring the main status logic.

### Is the route `/stock/` or `/stocks/`?

The correct gateway route is:

- `/stock/`

For example:

- `GET /stock/info`
- `GET /stock/exchange/info`
