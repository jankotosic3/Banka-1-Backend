# Banka-1-Backend

## Getting Started

### Prerequisites

- **Java 21**
- **Node.js** (for OpenAPI validation) — install `redocly`:
  ```bash
  npm install -g @redocly/cli
  ```
- **.NET SDK** (required if any .NET microservices are present)
- **Docker Desktop** (includes Docker Engine, CLI, and Compose) — [download here](https://www.docker.com/products/docker-desktop/)

### Setup after cloning

Run the setup script once to activate Git hooks:

```bash
./setup-hooks.sh
```

This configures Git to use the hooks in `.github/githooks/`.

> **This step is required.** Without it, pre-push checks will not run.

### Running the Main Infrastructure

To start the shared infrastructure services (PostgreSQL, RabbitMQ, notification-service, and user-service), run from the root folder:

```bash
docker compose -f .\setup\docker-compose.yml up -d
```

This will start:
- **PostgreSQL** — the main database
- **RabbitMQ** — the message broker with management UI
- **Notification Service** — for handling notifications
- **User Service** — for managing user accounts and authentication

---

## Pre-push Hooks

Every `git push` automatically runs four checks. The push is aborted if any check fails.

### 1. Unit and Integration Tests

Runs all tests across all services. Test commands are auto-detected by build system:

| Build system | Detection | Command |
|---|---|---|
| Gradle (root multi-project) | `gradlew` + `settings.gradle` | `./gradlew test` |
| Gradle (per-service) | `gradlew` | `./gradlew test` |
| Maven | `pom.xml` | `./mvnw verify` |
| Node.js | `package.json` | `npm ci && npm test` |
| Go | `go.mod` | `go test ./...` |
| Python | `requirements.txt` / `pyproject.toml` | `python -m pytest` |
| .NET | `*.sln` / `*.csproj` | `dotnet test` |

Unit and integration tests are both placed in `src/test/java` (for JVM services).

### 2. Lint

Java services use Checkstyle for basic code style validation.

Current checks include:
- line length
- wildcard imports
- required braces
- whitespace rules
- unused imports

Linting runs automatically during:
- pre-push hooks
- GitHub Actions CI

### 3. Documentation

Every microservice must have:

**`README.md`** should contain:
- a top-level title
- Docker Compose usage instructions
- `.env` / environment variable documentation
- Endpoint and/or event examples
- at least one code block

**OpenAPI spec** at one of these paths:
```
<service>/docs/openapi.yml
<service>/docs/openapi.yaml
<service>/src/main/resources/openapi.yml
<service>/src/main/resources/openapi.yaml
```

Requires `redocly` to be installed (see Prerequisites). The spec is validated with `redocly lint`.

**Skipping documentation checks** (e.g. for shared libraries that have no HTTP API, docker-compose, or environment config):

```bash
touch <service>/.skip-docs      # skips README section checks
touch <service>/.skip-openapi   # skips OpenAPI spec requirement and validation
```

### 4. Docker Build Validation

Runs `docker compose build --no-cache` inside each service directory that contains a `docker-compose.yml`.

Requires:
- Docker installed and running
- `docker-compose.yml` present inside the service directory

If either is missing for a given service, that service's Docker check is skipped with a warning.

---

## Adding a New Service

Follow these steps when adding a new microservice to the repo. The pre-push hook will enforce all of them automatically.

### 1. Create the service directory

Place the service at the repo root:

```
Banka-1-Backend/
├── company-observability-starter/
├── your-new-service/       ← here
└── ...
```

### 2. Register it in `settings.gradle` (Gradle services only)

If your service is a Gradle project, add it to `settings.gradle` at the repo root:

```gradle
include 'company-observability-starter'
include 'your-new-service'
```

This makes it a Gradle subproject so the root `./gradlew test` picks it up automatically.

For non-Gradle services (Maven, Node.js, Go, Python, .NET), no changes to `settings.gradle` are needed — the hook detects them automatically by their build files.

### 3. Add a `README.md`

Every service **must** have a `README.md` that includes:

- **A top-level title** — the service name as an H1 heading
- **Docker Compose instructions** — how to run the service with Docker Compose
- **Environment variables** — list all `.env` variables or `application.yml` config the service needs
- **Endpoints or events** — at least one example request/response or event payload
- **At least one code block** — example command, config, request, or payload

Example structure:

```markdown
# Your New Service

## Docker Compose

cd your-new-service
docker compose up --build

## Environment Variables

| Variable      | Description          | Default |
|---------------|----------------------|---------|
| SERVER_PORT   | Port the service runs on | 8080 |
| DATABASE_URL  | JDBC connection URL  | —       |

## API / Events

POST /api/v1/resource
{ "field": "value" }
```

### 4. Add an OpenAPI spec

Place the spec at one of these paths:

```
your-new-service/docs/openapi.yml
your-new-service/docs/openapi.yaml
your-new-service/src/main/resources/openapi.yml
your-new-service/src/main/resources/openapi.yaml
```

The spec is validated with `redocly lint` on every push.

If your service has **no HTTP API** (e.g. a worker or shared library), skip this requirement:

```bash
touch your-new-service/.skip-openapi
```

To skip README section checks (e.g. for shared libraries):

```bash
touch your-new-service/.skip-docs
```

### 5. Add a `Dockerfile` and `docker-compose.yml`

Each service manages its own Docker setup. Add both files inside your service directory:

**`Dockerfile`** — defines how to build the image.

**`docker-compose.yml`** — defines how to run the service locally:
```yaml
services:
  your-new-service:
    build:
      context: .
      dockerfile: Dockerfile
    ports:
      - "8080:8080"
    env_file:
      - .env
```

To run your service locally:
```bash
cd your-new-service
docker compose up --build
```

> Note: A unified root-level `docker-compose.yml` covering all services will be introduced
> later in the project. At that point this step will change.

### 6. Make sure tests pass

The hook runs tests automatically based on the detected build system. Ensure your service has passing tests before pushing:

| Build system | Required file | Test command run by hook |
|---|---|---|
| Gradle (root multi-project) | `settings.gradle` include | `./gradlew test` (from root) |
| Maven | `pom.xml` | `./mvnw verify` |
| Node.js | `package.json` | `npm ci && npm test` |
| Go | `go.mod` | `go test ./...` |
| Python | `requirements.txt` / `pyproject.toml` | `python -m pytest` |
| .NET | `*.sln` / `*.csproj` | `dotnet test` |

### 7. Enable linting (Java/Gradle services)

If your service is a Gradle-based Java service and should participate in shared lint checks, add Checkstyle support to its `build.gradle`:

```gradle
plugins {
    id 'checkstyle'
}

checkstyle {
    configFile = rootProject.file('checkstyle.xml')
}

tasks.withType(Checkstyle).configureEach {
    ignoreFailures = true
}
```

### 8. Using the observability starter (optional)

If your service is a Spring Boot service, add the shared observability library:

```gradle
dependencies {
    implementation project(':company-observability-starter')
}
```

Set the application name in `application.yml`:

```yaml
spring:
  application:
    name: your-new-service
```

See `company-observability-starter/README.md` for full configuration options.

### 9. Register with the API Gateway (Crucial for ecosystem integration)

For your service to be reachable by frontends or outside traffic within the full ecosystem, it must be registered with NGINX. 

1. **Define the internal port:** Add it to the root `.env` file (e.g., `NEW_SERVICE_PORT=8085`).
2. **Add to `setup/docker-compose.yml`:** Use `expose` to keep the port hidden from the public, but accessible to the Gateway:
   ```yaml
     your-new-service:
       build: ...
       env_file:
         - .env
       expose:
         - "${NEW_SERVICE_PORT:-8085}"
       networks:
         - banka-network
   ```
3. **Map the route in `api-gateway/default.conf.template`:** NGINX will dynamically read the `.env` variable on startup and route the traffic.
   ```nginx
   upstream new_service {
       server your-new-service:${NEW_SERVICE_PORT};
   }

   server {
       # ...
       location /new-service-path {
           proxy_pass http://new_service;
       }
   }
   ```

