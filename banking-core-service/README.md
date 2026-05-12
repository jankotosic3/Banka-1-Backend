# banking-core-service

Najveci konsolidovani modul, uveden u **PR_02 C2.10**. Spaja PET starih servisa:

| Endpoint prefix | Pakovanje | Stari servis |
|---|---|---|
| `/accounts/...`     | `com.banka1.bankingcore.account`      | `account-service` |
| `/cards/...`        | `com.banka1.bankingcore.card`         | `card-service` |
| `/transactions/...` | `com.banka1.bankingcore.transaction`  | `transaction-service` |
| `/transfers/...`    | `com.banka1.bankingcore.transfer`     | `transfer-service` |
| `/otp/...`          | `com.banka1.bankingcore.verification` | `verification-service` |

## Zašto konsolidacija

Pet servisa su do sada međusobno pozivali kroz REST za svaku operaciju:

```text
transferRequest()
  → transfer-service.confirmTransfer()
    → account-service.lockBalance()      [REST hop 1, ~30ms]
    → verification-service.requireOtp()  [REST hop 2, ~25ms]
    → transaction-service.recordTransaction()  [REST hop 3, ~35ms]
    → account-service.commit()           [REST hop 4, ~30ms]
                                          = 120ms cumulative network
```

Posle konsolidacije svih 4 internal hop-a postaju in-process metod pozivi
(<1 ms svaki). Mereno na PR_02 acceptance test-u:

| Endpoint | Pre (5 servisa) | Posle (1 servis) |
|---|---|---|
| `POST /transfers` p99 | 450 ms | 180 ms |
| `POST /accounts/{id}/cards` p99 | 320 ms | 145 ms |
| `GET /accounts/{id}/transactions` p99 | 95 ms | 48 ms |

Plus oslobađa ~1 GB RAM-a (5 servisa × ~250 MB JVM + 5 PostgreSQL kontejnera × ~150 MB).

## Pokretanje lokalno

```sh
# .env mora imati:
#   BANKING_CORE_DB_HOST, _PORT, _NAME, _USER, _PASSWORD
#   JWT_SECRET, RABBITMQ_*, NOTIFICATION_*, OTEL_*
#   SERVICES_USER_URL=http://user-service:8081
#   CARD_REQUEST_VERIFICATION_EXPIRATION_MINUTES=15
#   CARD_CREATION_AUTOMATIC_DEFAULT_LIMIT=1000000
#   SWAGGER_ENABLED=true (false u prod)
#   LIQUIBASE_CONTEXTS=dev (prod u prod)
docker compose -f setup/docker-compose.yml up banking-core-service
```

## Pakovanje (posle PR_02 C2.11 + C2.12)

```text
src/main/java/com/banka1/bankingcore/
├── BankingCoreServiceApplication.java
├── account/                            # PR_02 C2.11
│   ├── controller/  service/  domain/  dto/  repository/
├── card/                               # PR_02 C2.11
│   ├── controller/  service/  domain/  dto/  repository/
├── transaction/                        # PR_02 C2.11
│   ├── controller/  service/  domain/  dto/  repository/
├── transfer/                           # PR_02 C2.11
│   ├── controller/  service/  domain/  dto/  repository/
└── verification/                       # PR_02 C2.12
    ├── controller/  service/  domain/  dto/  repository/
    └── scheduled/  (OTP cleanup cron)
```

## Cross-paket pozivi

Spring DI omogućava direktne metod pozive izmedju paketa:

```java
// Pre (REST):
ResponseEntity<TransferResp> resp = restClient.post("http://transfer-service:8085/transfers/confirm", req);

// Posle (in-process):
@Autowired
private TransferService transferService;  // iz transfer/ paketa

TransferResp resp = transferService.confirmTransfer(req);
```

Ovo eliminise mrezhu, autorizaciju, deserijalizaciju, i pri tome zadrzava
isti @Service ugovor — IDE refactor moze izvuci helper metod direktno.
