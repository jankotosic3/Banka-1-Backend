# Discord Alert Bot

Small Node.js service that receives Alertmanager webhook POSTs and forwards
each alert as a Discord DM to the developer running this stack.

It is part of the Monitoring / Alerting pipeline. All backend services are Go and do
not emit application metrics, so they are monitored **black-box** via exporters:

```
blackbox-exporter  (HTTP health probes)  ─┐
postgres-exporter  (DB connections)       ├─> Prometheus (setup/prometheus-rules.yml)
rabbitmq-exporter  (queue depth)          ─┘     -> Alertmanager (setup/alertmanager.yml)
                                                   -> POST http://discord-alert-bot:9094/alert
                                                     -> Discord API -> DM to DEVELOPER_DISCORD_ID
```

## Notifications it sends

The bot forwards every alert defined in `setup/prometheus-rules.yml`, rendered as a
Discord DM in one of four shapes:

| Shape | Looks like | When |
|---|---|---|
| 🔴 critical + Component | `🔴 [FIRING] <name>` + `Component: infrastructure` | an infra component is down |
| 🔴 critical + Service | `🔴 [FIRING] <name>` + `Service: <svc>` | a service is down |
| ⚠️ warning + Service | `⚠️ [FIRING] <name>` + `Service: <svc>` | a soft threshold was crossed |
| ✅ resolved | `✅ [RESOLVED] <name>` … | the condition cleared (any alert) |

### Alert catalogue

| Alert | Severity | Source | Fires when |
|---|---|---|---|
| `ServiceDown` | 🔴 critical | blackbox-exporter | a service health probe fails — one per service: `notification`, `user`, `banking-core`, `market`, `trading`, `credit`, `saga-orchestrator`, `interbank` |
| `OtelCollectorDown` | 🔴 critical | Prometheus `up` | the OTel collector is unreachable |
| `AlertmanagerDown` | 🔴 critical | Prometheus `up` | Alertmanager is unreachable (self-referential — delivered on recovery) |
| `ServiceProbeSlow` | ⚠️ warning | blackbox-exporter | a service health probe is slow (> 1.5s) |
| `PostgresConnectionsHigh` | ⚠️ warning | postgres-exporter | active DB connections > 80% of `max_connections` (200) |
| `RabbitMQBacklog` | ⚠️ warning | rabbitmq-exporter | a queue has > 1000 unconsumed messages |
| `RabbitMQNoConsumers` | ⚠️ warning | rabbitmq-exporter | a queue has messages but 0 consumers |

Every alert also sends a ✅ **RESOLVED** DM when its condition clears
(`send_resolved: true` in `setup/alertmanager.yml`).

### Example DM

```
🔴 [FIRING] ServiceDown
credit-service je DOWN
Health proba ka http://banka_credit_service:8089/health ne uspeva. Servis je verovatno pao ili ne odgovara.
Service: credit-service
```

## How to set up the Discord bot account (one-time, manual)

You do this once in your browser to obtain a token. The bot account is shared
by the team — only the `DEVELOPER_DISCORD_ID` differs per developer.

1. Open <https://discord.com/developers/applications> and log in with Discord.
2. Click **New Application**, name it (e.g. `Banka-1 Alerts`), create.
3. Left sidebar → **Bot** → **Reset Token** → copy the token (shown only once).
   This is `DISCORD_BOT_TOKEN`.
4. Left sidebar → **OAuth2** → **URL Generator**:
   - Scopes: tick `bot`
   - Bot Permissions: leave empty (DMs do not require a guild permission)
5. Open the generated URL in a new tab, pick the Discord server where the team
   is, click **Authorize**. The bot now appears as a member (offline until you
   `docker compose up` this stack).

To obtain your own `DEVELOPER_DISCORD_ID`:

1. Discord → **User Settings** → **Advanced** → enable **Developer Mode**.
2. Right-click your avatar → **Copy User ID**.

## Docker Compose

This service is started together with the rest of the stack:

```bash
docker compose -f ./setup/docker-compose.yml up -d discord-alert-bot
```

It is also pulled in automatically by `alertmanager` via `depends_on`.

To build the image standalone from the repo root:

```bash
docker build -f discord-alert-bot/Dockerfile -t discord-alert-bot .
```

## Environment variables

| Variable | Required | Description |
|---|---|---|
| `DISCORD_BOT_TOKEN` | yes | Bot token from the Discord Developer Portal. |
| `DEVELOPER_DISCORD_ID` | yes | Numeric Discord user ID that will receive the DMs. |
| `PORT` | no | HTTP port the bot listens on (default `9094`). |

Set these in `setup/.env` (see `setup/.env.example` for the template).

## Endpoints (internal, banka-network only)

### `POST /alert`

Consumes the Alertmanager webhook payload. Example body (truncated):

```json
{
  "status": "firing",
  "alerts": [
    {
      "status": "firing",
      "labels": {
        "alertname": "BankingServiceDown",
        "severity": "critical",
        "service": "banking-service"
      },
      "annotations": {
        "summary": "banking-service is not reporting metrics",
        "description": "banking-service has not exported any JVM metrics for 2+ minutes."
      },
      "startsAt": "2026-05-19T10:00:00Z"
    }
  ]
}
```

Response: `{ "status": "ok", "delivered": <n> }` on success.

### `GET /health`

Returns `200 { "ready": true }` once the bot is logged in to Discord,
`503 { "ready": false }` while still connecting.

## Local manual test

After `docker compose up`, fake an alert from your shell:

```bash
curl -X POST http://localhost:9093/api/v2/alerts \
  -H "Content-Type: application/json" \
  -d '[{"labels":{"alertname":"ManualTest","severity":"warning"},"annotations":{"summary":"hello from curl"}}]'
```

Alertmanager will forward it to the bot, which will DM you on Discord.

## Triggering real alerts (demo mode)

Two helper scripts in `setup/` let you fire each notification on a running stack —
handy for a demo or a screen recording. They are Windows PowerShell scripts.

### `setup/demo-mode.ps1` — fast-firing thresholds

Production rules use realistic thresholds and multi-minute `for` windows, so warning
alerts take a while to fire. Demo mode swaps in a copy of the rules with lowered
thresholds and a short `for` (~15-20s) so everything fires within ~30-60s.

```powershell
./setup/demo-mode.ps1 on        # apply demo thresholds, reload Prometheus
./setup/demo-mode.ps1 off       # restore production rules
./setup/demo-mode.ps1 status    # show which set is active
```

`on` snapshots the live `prometheus-rules.yml` into a transient
`prometheus-rules.yml.bak` and swaps in `prometheus-rules.demo.yml`; `off` restores
the snapshot and deletes it. The standard `prometheus-rules.yml` stays the single
source of truth (never overwritten permanently).

Trigger / resolve pairs (run `demo-mode.ps1 on` first for the warning alerts):

```powershell
# ServiceDown — fire / resolve
docker stop  banka_credit_service
docker start banka_credit_service

# PostgresConnectionsHigh — fire / resolve
1..35 | ForEach-Object { Start-Process -WindowStyle Hidden docker -ArgumentList 'exec banka_postgres psql -U postgres -d postgres -c "select pg_sleep(300)"' }
docker exec banka_postgres psql -U postgres -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE query ILIKE 'select pg_sleep%' AND pid <> pg_backend_pid();"

# RabbitMQBacklog + RabbitMQNoConsumers — fire / resolve
$h = @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("guest:guest")) }
$body = '{"properties":{},"routing_key":"demo.backlog","payload":"hello","payload_encoding":"string"}'
irm -Method Put "http://localhost:15672/api/queues/%2F/demo.backlog" -Headers $h -ContentType application/json -Body '{"durable":true}'
1..20 | ForEach-Object { irm -Method Post "http://localhost:15672/api/exchanges/%2F/amq.default/publish" -Headers $h -ContentType application/json -Body $body | Out-Null }
irm -Method Delete "http://localhost:15672/api/queues/%2F/demo.backlog/contents" -Headers $h
irm -Method Delete "http://localhost:15672/api/queues/%2F/demo.backlog" -Headers $h
```

> The RabbitMQ `Put` (declare queue) must run before publishing — messages sent to a
> non-existent queue are silently dropped.

### `setup/probe-slow-demo.ps1` — trigger `ServiceProbeSlow`

`ServiceProbeSlow` needs a slow health probe, which won't happen on its own. This
script injects +1500ms latency on credit-service's health probe via the existing
`toxiproxy` container and temporarily re-points the blackbox probe through it.

```powershell
./setup/probe-slow-demo.ps1 on     # inject latency -> ⚠️ ServiceProbeSlow in ~40s
./setup/probe-slow-demo.ps1 off    # remove latency, revert probe -> ✅ resolved
```

Run `demo-mode.ps1 on` first: in demo mode the threshold is `> 0.5s`, which the
injected 1.5s clearly crosses (production threshold is `> 1.5s`, only borderline).

> These scripts call the toxiproxy admin API with a non-browser `User-Agent`
> (toxiproxy rejects browser-like agents), and a Prometheus reload happens on each
> toggle.

## Tests

```bash
cd discord-alert-bot
npm install
npm test
```

Uses the built-in Node test runner; no extra framework needed.
