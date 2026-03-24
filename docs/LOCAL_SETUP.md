# CopiProxy Local Setup Guide (Your GitHub Account)

This guide walks you through configuring and running CopiProxy locally using your own GitHub account.

## 1) Prerequisites

- GitHub account with active Copilot entitlement (Pro/Business/Enterprise).
- Java 21 installed and available on PATH.
- Maven 3.9+ installed.
- `curl` installed.

Verify:

```bash
java -version
mvn -version
curl --version
```

## 2) Clone and build

```bash
git clone https://github.com/happysantoo/copilot-proxy.git copiproxy
cd copiproxy
mvn clean package -DskipTests
```

If build succeeds, you should have `target/copiproxy-0.1.0.jar`.

## 3) Configure runtime

**SQLite database file** defaults to `./copiproxy.db` (relative to the directory you run the app from). Configure it in either place:

- **YAML:** `copiproxy.storage.sqlite-path` in `src/main/resources/application.yml` (or your own override file / profile).
- **Environment:** `COPROXY_STORAGE_SQLITE_PATH` (preferred), or `DATABASE_PATH` (legacy — same role).

Other common settings:

- `PORT` (default `3000`)
- `COPIPROXY_DEFAULT_MODEL` (default `claude-opus-4`) -- model injected when the client omits `model` from the request body
- `LOG_LEVEL` (default `INFO`)
- `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://localhost:4318/v1/traces`)

Example:

```bash
export PORT=3000
export COPROXY_STORAGE_SQLITE_PATH=./copiproxy.db
# or: export DATABASE_PATH=./copiproxy.db
export COPIPROXY_DEFAULT_MODEL=claude-opus-4
export LOG_LEVEL=INFO
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318/v1/traces
```

## 4) Start the proxy

Choose one:

```bash
mvn spring-boot:run
```

or

```bash
java -jar target/copiproxy-0.1.0.jar
```

## 5) Verify service health

```bash
curl http://localhost:3000/health
curl http://localhost:3000/actuator/health
```

Expected:

- `/health` -> `{"status":"ok"}`
- `/actuator/health` -> status `UP`

## 6) Add your GitHub token/key

You have two options.

### Option A: Manual key add (recommended for scripting)

If you already have a token/key that works with Copilot internal token exchange, add it directly:

```bash
curl -X POST http://localhost:3000/admin/api-keys \
  -H 'content-type: application/json' \
  -d '{"name":"my-github-key","key":"<YOUR_GITHUB_KEY>"}'
```

Then set it as default:

```bash
curl http://localhost:3000/admin/api-keys
```

Copy the `id` from the response, then:

```bash
curl -X POST http://localhost:3000/admin/api-keys/default \
  -H 'content-type: application/json' \
  -d '{"id":"<API_KEY_ID>"}'
```

### Option B: Device flow (login in browser)

Start device flow stream:

```bash
curl -N http://localhost:3000/admin/api-keys/device-flow
```

You will receive events including:

- `userCode`
- `verificationUri`

Open `verificationUri`, enter `userCode`, approve access.  
On success, CopiProxy stores the resulting token as a key record (you can then set it as default with `/admin/api-keys/default`).

## 7) Test OpenAI-compatible endpoints

### List models

```bash
curl http://localhost:3000/api/models
```

This returns the live catalog from Copilot, including Claude Sonnet/Opus and GPT models your subscription has access to.

### Chat completion using default model (claude-opus-4)

Omit `model` and CopiProxy injects the configured default:

```bash
curl -X POST http://localhost:3000/api/chat/completions \
  -H 'content-type: application/json' \
  -d '{
    "messages":[{"role":"user","content":"Say hello"}]
  }'
```

### Chat completion with explicit model

```bash
curl -X POST http://localhost:3000/api/chat/completions \
  -H 'content-type: application/json' \
  -d '{
    "model":"claude-sonnet-4.6",
    "messages":[{"role":"user","content":"Say hello"}]
  }'
```

Available Claude model IDs: `claude-opus-4`, `claude-sonnet-4.6`, `claude-sonnet-4.5`, `claude-sonnet-4-20250514`.
GPT models (e.g. `gpt-4o`, `gpt-4.1`) also work. See [USING_COPILOT_MODELS.md](USING_COPILOT_MODELS.md) for full details.

### Chat completion with explicit key override

```bash
curl -X POST http://localhost:3000/api/chat/completions \
  -H 'content-type: application/json' \
  -H 'authorization: Bearer <YOUR_GITHUB_KEY>' \
  -d '{
    "model":"claude-opus-4",
    "messages":[{"role":"user","content":"Say hello"}]
  }'
```

### Dummy token for tools that require non-empty API key

Some clients require an API key value even when using a default server key.
Use `_`:

```bash
-H 'authorization: Bearer _'
```

CopiProxy interprets `_` as "use configured default key".

## 8) Configure OpenAI-compatible clients

Any tool that supports a custom OpenAI base URL can use CopiProxy:

- **Base URL:** `http://localhost:3000/api`
- **API key:** your GitHub key, or `_` if a default key is configured in CopiProxy.
- **Model:** any ID from `GET /api/models` (e.g. `claude-opus-4`, `claude-sonnet-4.6`, `gpt-4o`). If omitted, CopiProxy injects the configured default (`claude-opus-4`).

> **Note:** Claude Code uses the Anthropic Messages API (`/v1/messages`), not OpenAI Chat Completions. CopiProxy does **not** implement that protocol and cannot drive Claude Code directly. See [CLAUDE_CODE_COMPATIBILITY.md](CLAUDE_CODE_COMPATIBILITY.md) for a detailed gap analysis.

## 9) OpenTelemetry / monitoring

Available endpoints:

- `GET /actuator/health`
- `GET /actuator/metrics`
- `GET /actuator/prometheus`

Tracing is exported to `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://localhost:4318/v1/traces`).

If no OTLP collector is running, proxy still functions; trace export attempts may log warnings.

## 10) Troubleshooting

- **401 / missing API key**: ensure you set a default key or pass `authorization: Bearer <key>`.
- **400 model errors**: check model availability under your GitHub Copilot account settings and verify with `/api/models`.
- **429 rate limited**: Copilot Pro has per-model rate limits; see [USING_COPILOT_MODELS.md](USING_COPILOT_MODELS.md) for details.
- **No models returned**: key may be invalid/expired; refresh metadata using:
  - `POST /admin/api-keys/{id}/refresh-meta`
- **Port conflict**: change `PORT`, e.g. `export PORT=3100`.
- **Database issues**: remove `copiproxy.db` for a clean state (you will need to re-add keys).

## 11) Security recommendations

- Do not commit personal tokens.
- Restrict local network exposure (bind/run locally only).
- Rotate keys if leaked.
- For team/shared usage, add auth in front of `/admin/*` endpoints before exposing beyond localhost.
