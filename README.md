# CopiProxy - GitHub Copilot as OpenAI-Compatible API (Java 21)

A Java 21 local proxy that exposes your GitHub Copilot quota as OpenAI-compatible APIs.
No frontend required -- everything is managed via REST endpoints and CLI.

## Features

- OpenAI-compatible proxy endpoints:
  - `POST /api/chat/completions` (streaming)
  - `GET /api/models`
- Proxies to `https://api.githubcopilot.com`
- API key management (REST):
  - `GET /admin/api-keys`
  - `POST /admin/api-keys`
  - `PATCH /admin/api-keys/{id}`
  - `DELETE /admin/api-keys/{id}`
  - `POST /admin/api-keys/default`
  - `POST /admin/api-keys/{id}/refresh-meta`
- GitHub device flow authentication (SSE):
  - `GET /admin/api-keys/device-flow`
- Default token + dummy token `_` support
- SQLite persistence
- Java 21 virtual threads
- OpenTelemetry tracing and metrics via Spring Boot Actuator

## Prerequisites

- Java 21+
- Maven 3.9+

## Full Setup Guide

- For a complete step-by-step guide (your own GitHub account, device flow, client setup, troubleshooting), see `docs/LOCAL_SETUP.md`.

## Run

```bash
mvn spring-boot:run
```

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `PORT` | `3000` | HTTP server port |
| `DATABASE_PATH` | `./copiproxy.db` | SQLite database file path |
| `LOG_LEVEL` | `INFO` | Root log level |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP trace collector endpoint |

## Quick start

### 1. Add a GitHub token

```bash
curl -X POST http://localhost:3000/admin/api-keys \
  -H 'content-type: application/json' \
  -d '{"name":"my-key","key":"ghu_xxx"}'
```

### 2. Set it as default

```bash
curl -X POST http://localhost:3000/admin/api-keys/default \
  -H 'content-type: application/json' \
  -d '{"id":"<api-key-id>"}'
```

### 3. Use the proxy

```bash
curl -X POST http://localhost:3000/api/chat/completions \
  -H 'content-type: application/json' \
  -d '{"model":"gpt-4.1","messages":[{"role":"user","content":"hi"}]}'
```

### Or use device flow (interactive GitHub login)

```bash
curl -N http://localhost:3000/admin/api-keys/device-flow
```

Follow the SSE events to complete authorization in your browser.

## Observability

- Health: `GET /actuator/health`
- Metrics: `GET /actuator/metrics`
- Prometheus: `GET /actuator/prometheus`
- Distributed tracing exported via OTLP

## License

Apache License 2.0 — see [LICENSE](LICENSE).
