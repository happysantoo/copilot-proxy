# CopiProxy - GitHub Copilot as OpenAI-Compatible API (Java 21)

A Java 21 local proxy that exposes your GitHub Copilot quota as OpenAI-compatible APIs.
No frontend required -- everything is managed via REST endpoints and CLI.

## Features

- OpenAI-compatible proxy endpoints:
  - `POST /api/chat/completions` (streaming)
  - `GET /api/models`
- **Claude Sonnet and Opus models** via Copilot Pro (default: `claude-opus-4`); see [docs/USING_COPILOT_MODELS.md](docs/USING_COPILOT_MODELS.md)
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

## Code coverage (JaCoCo)

After tests, an HTML report is generated automatically when you build through **`package`** or later (for example `mvn clean package` or `mvn verify`):

- **HTML:** `target/site/jacoco/index.html`
- **XML (for CI):** `target/site/jacoco/jacoco.xml`

To refresh only the report using the last `target/jacoco.exec` (for example you ran `mvn test` already):

```bash
mvn jacoco:report
```

## Configuration

**SQLite file location** (sensible default: `./copiproxy.db` in the process working directory):

- In config: set `copiproxy.storage.sqlite-path` in [`application.yml`](src/main/resources/application.yml) (or an external `application.yml` / profile).
- Via environment (either works; first wins in the chain below):
  - `COPROXY_STORAGE_SQLITE_PATH` — preferred name
  - `DATABASE_PATH` — legacy alias

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `PORT` | `3000` | HTTP server port |
| `COPROXY_STORAGE_SQLITE_PATH` | (see `application.yml`) | SQLite database file path |
| `DATABASE_PATH` | `./copiproxy.db` | Legacy alias for the SQLite file path |
| `COPIPROXY_DEFAULT_MODEL` | `claude-opus-4` | Model injected when client omits `model` from the request body |
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
  -d '{"model":"claude-opus-4","messages":[{"role":"user","content":"hi"}]}'
```

Omit `"model"` to use the configured default (`claude-opus-4`). See [docs/USING_COPILOT_MODELS.md](docs/USING_COPILOT_MODELS.md) for all supported Claude model IDs.

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
