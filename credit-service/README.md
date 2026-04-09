# Credit Service – Upravljanje kreditima

Mikroservis za upravljanje kreditima i kreditnim zahtjevima klijenata. Podržava različite vrste kredita (hipoteke, potrošački krediti, itd.), odobravanje zahtjeva, i detaljnog praćenja kredita. Deo Banka 1 backend sistema, izgrađen na **Spring Boot 4.0.3** sa **PostgreSQL** bazom i **Liquibase** migracijama.

---

## Docker Compose

### Opcija 1: Hibridni režim (preporučeno za razvoj)

Pokrenite samo infrastrukturu u Dockeru:

```bash
cd credit-service
docker compose -f docker-compose_intelij.yml up -d
```

Zatim pokrenite aplikaciju iz IntelliJ (`CreditServiceApplication`). Aplikacija koristi fallback vrednosti iz `application.properties`.

### Opcija 2: Puni Docker paket (ceo sistem)

```bash
docker compose -f setup/docker-compose.yml up -d --build credit-service
```

Servis je dostupan na `http://localhost:8089` (direktno) ili `http://localhost/credits/` (kroz API gateway).

**Korisne komande:**
```bash
docker compose -f setup/docker-compose.yml logs -f credit-service     # Praćenje logova
docker compose -f setup/docker-compose.yml down                       # Gašenje svih kontejnera
docker compose -f setup/docker-compose.yml down -v                    # Gašenje + brisanje baze
```

---

## Environment Variables

Kreirati `.env` fajl u `setup/` folderu (primer u `setup/.env.example`):

| Varijabla | Opis | Primer |
|---|---|---|
| `CREDIT_SERVER_PORT` | Port na kome servis sluša | `8089` |
| `CREDIT_DB_HOST` | Hostname baze podataka | `postgres_credit` |
| `CREDIT_DB_PORT` | Port baze podataka | `5438` |
| `CREDIT_DB_NAME` | Naziv baze podataka | `creditdb` |
| `CREDIT_DB_USER` | Korisničko ime baze | `postgres` |
| `CREDIT_DB_PASSWORD` | Lozinka baze | `postgres` |
| `JWT_SECRET` | HMAC-SHA256 secret (isti kao ostali servisi) | `my_secret_key` |
| `SERVICES_USER_URL` | URL employee-service-a (za proveru zaposlenog) | `http://employee-service` |
| `SERVICES_CARD_URL` | URL card-service-a | `http://card-service:8087` |
| `SERVICES_VERIFICATION_URL` | URL verification-service-a | `http://verification-service` |
| `SERVICES_EXCHANGE_URL` | URL exchange-service-a | `http://exchange-service` |
| `SERVICES_ACCOUNT_URL` | URL account-service-a | `http://account-service:8084` |
| `SKIP_VERIFICATION` | Da li preskočiti verifikaciju | `true` |
| `RABBITMQ_HOST` | Hostname RabbitMQ brokera | `rabbitmq` |
| `RABBITMQ_PORT` | Port RabbitMQ brokera | `5672` |
| `RABBITMQ_MANAGEMENT_PORT` | Management port RabbitMQ | `15672` |
| `RABBITMQ_USERNAME` | Korisničko ime RabbitMQ | `guest` |
| `RABBITMQ_PASSWORD` | Lozinka RabbitMQ | `guest` |
| `NOTIFICATION_QUEUE` | Naziv RabbitMQ queue-a za notifikacije | `notification-service-queue` |
| `NOTIFICATION_EXCHANGE` | Naziv RabbitMQ exchange-a | `employee.events` |
| `NOTIFICATION_ROUTING_KEY` | Routing key za notifikacije | `employee.#` |

---

## API Endpoints

Svi endpointi zahtevaju Bearer JWT token u headeru:
```
Authorization: Bearer <token>
```

### Klijentski endpointi (`/api/loans/*`) — zahteva `CLIENT_BASIC` rolu

#### Kreiranje kreditnog zahtjeva

```
POST /api/loans/requests
Content-Type: application/json

{
  "loanType": "MORTGAGE",
  "amount": 50000.00,
  "currency": "RSD",
  "term": 120,
  "interestType": "FIXED"
}
```

**Odgovor (201 Created):**
```json
{
  "id": 1,
  "clientId": 42,
  "loanType": "MORTGAGE",
  "amount": 50000.00,
  "currency": "RSD",
  "term": 120,
  "interestType": "FIXED",
  "status": "PENDING",
  "createdAt": "2024-04-10T10:30:00"
}
```

#### Pregled sopstvenih kredita

```
GET /api/loans/client?page=0&size=10
```

**Parametri:**
- `page` (int, default: 0) - Broj stranice
- `size` (int, default: 10, max: 100) - Broj stavki po stranici

**Odgovor (200 OK):**
```json
{
  "content": [
    {
      "id": 1,
      "loanNumber": 1001,
      "amount": 50000.00,
      "currency": "RSD",
      "monthlyPayment": 500.00,
      "remainingBalance": 45000.00,
      "status": "ACTIVE",
      "startDate": "2024-04-10",
      "endDate": "2034-04-10"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "currentPage": 0
}
```

#### Detalji kredita

```
GET /api/loans/{loanNumber}
```

**Odgovor (200 OK):**
```json
{
  "id": 1,
  "loanNumber": 1001,
  "clientId": 42,
  "loanType": "MORTGAGE",
  "amount": 50000.00,
  "currency": "RSD",
  "term": 120,
  "monthlyPayment": 500.00,
  "remainingBalance": 45000.00,
  "interestRate": 4.5,
  "interestType": "FIXED",
  "status": "ACTIVE",
  "startDate": "2024-04-10",
  "endDate": "2034-04-10",
  "createdAt": "2024-04-10T10:30:00"
}
```

---

### Zaposleni endpointi (`/api/loans/*`) — zahteva `BASIC` rolu

#### Odobravanje kreditnog zahtjeva

```
PUT /api/loans/requests/{id}/approve
```

**Odgovor (200 OK):**
```
Kreditni zahtjev je uspješno odobren.
```

#### Odbijanje kreditnog zahtjeva

```
PUT /api/loans/requests/{id}/decline
```

**Odgovor (200 OK):**
```
Kreditni zahtjev je uspješno odbijen.
```

#### Pregled svih kreditnih zahtjeva (sa filterima)

```
GET /api/loans/requests?vrstaKredita=MORTGAGE&brojRacuna=RS123&page=0&size=10
```

**Parametri:**
- `vrstaKredita` (string, optional) - Vrsta kredita (npr. MORTGAGE, CONSUMER_LOAN)
- `brojRacuna` (string, optional) - Broj računa za filtriranje
- `page` (int, default: 0) - Broj stranice
- `size` (int, default: 10, max: 100) - Broj stavki po stranici

**Odgovor (200 OK):**
```json
{
  "content": [
    {
      "id": 1,
      "clientId": 42,
      "loanType": "MORTGAGE",
      "amount": 50000.00,
      "currency": "RSD",
      "term": 120,
      "monthlyPayment": 500.00,
      "interestRate": 4.5,
      "status": "PENDING",
      "createdAt": "2024-04-10T10:30:00"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "currentPage": 0
}
```

#### Pregled svih kredita (sa filterima)

```
GET /api/loans/all?vrstaKredita=MORTGAGE&brojRacuna=RS123&loanStatus=ACTIVE&page=0&size=10
```

**Parametri:**
- `vrstaKredita` (string, optional) - Vrsta kredita
- `brojRacuna` (string, optional) - Broj računa
- `loanStatus` (string, optional) - Status kredita (PENDING, APPROVED, DECLINED, ACTIVE, CLOSED)
- `page` (int, default: 0) - Broj stranice
- `size` (int, default: 10, max: 100) - Broj stavki po stranici

**Odgovor (200 OK):** Ista struktura kao `/api/loans/client`

---

## Baza podataka i Liquibase

Projekat koristi PostgreSQL i Liquibase za migracije šeme. Hibernate je postavljen na `validate` mod — ne kreira tabele automatski.

**Pravila migracija:**
- NIKADA ne menjati postojeće `.sql` fajlove koji su već pokrenuti
- Za izmenu šeme kreirati novi numerisani fajl (npr. `003-dodaj-polje.sql`) i prijaviti ga u `db.changelog-master.xml`

---

## Pokretanje testova

```bash
./gradlew :credit-service:test
```

Coverage izveštaj: `credit-service/build/reports/jacoco/test/html/index.html`

---

## Struktura projekta

```
credit-service/
├── src/
│   ├── main/
│   │   ├── java/com/banka1/credit_service/
│   │   │   ├── controller/          # REST kontroleri
│   │   │   ├── service/             # Poslovna logika
│   │   │   ├── domain/              # JPA entiteti
│   │   │   ├── dto/                 # DTO objekti (zahtjevi/odgovori)
│   │   │   ├── repository/          # Spring Data JPA repozitorijumi
│   │   │   ├── advice/              # Exception handlers
│   │   │   ├── rabbitMQ/            # RabbitMQ konfiguracija
│   │   │   ├── rest_client/         # Integracije sa drugim servisima
│   │   │   ├── security/            # Sigurnosna konfiguracija
│   │   │   └── swagger/             # Swagger/OpenAPI konfiguracija
│   │   └── resources/
│   │       ├── application.properties
│   │       └── db/changelog/        # Liquibase migracije
│   └── test/
│       └── java/                    # JUnit testovi
├── build.gradle.kts                # Gradle build konfiguracija
├── docker-compose_intelij.yml      # Docker za razvoj
├── Dockerfile                      # Docker build fajl
└── README.md                        # Ovaj fajl
```

---

## Tipske vrednosti

### Vrsta kredita (LoanType)
- `MORTGAGE` - Hipotekarna kredita
- `CONSUMER_LOAN` - Potrošački kredit
- `AUTO_LOAN` - Automobilski kredit
- `PERSONAL_LOAN` - Lični kredit

### Vrsta kamate (InterestType)
- `FIXED` - Fiksna kamatna stopa
- `VARIABLE` - Varijabilna kamatna stopa

### Status (Status)
- `PENDING` - U čekanju na odobrenje
- `APPROVED` - Odobren
- `DECLINED` - Odbijen
- `ACTIVE` - Aktivan kredit
- `CLOSED` - Zatvoreni kredit

---

## Integracije sa drugim servisima

Credit Service komunicira sa sledećim servisima:

- **Employee Service** - Verifikacija zaposlenih koji odobravaju kredite
- **Account Service** - Informacije o računima klijentata
- **Card Service** - Integracija sa kartičnim servisima
- **Verification Service** - Verifikacija podataka
- **Exchange Service** - Konverzija valuta

Komunikacija se dešava preko REST API poziva i RabbitMQ poruka za asinkrone operacije.

---

## Zakazane tasks

Servis koristi `@EnableScheduling` za periodic tasks. Proverite `service/` paket za detalje.

---

## Troubleshooting

### Port je već u upotrebi
```bash
# Pronađite proces koji koristi port 8089
netstat -ano | findstr :8089

# Ubijte proces
taskkill /PID <PID> /F
```

### Baza se ne konekcira
- Proverite da su pravi podaci u `.env` fajlu
- Proverite da je Docker pokrenut: `docker ps`
- Proverite da su kontejneri pokrenuti: `docker logs postgres_credit`

### Liquibase greške
- **Checksum error**: Nikada ne menjajte postojeće `.sql` fajlove
- Kreirajte novi numerisani fajl za nove migracije

---

## Licensing

Deo Banka 1 sistema

