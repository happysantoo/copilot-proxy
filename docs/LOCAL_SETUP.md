# CopiProxy Local Setup Guide (Your GitHub Account)

This guide walks you through configuring and running CopiProxy locally using your own GitHub account.

## 1) Prerequisites

- GitHub account with active Copilot entitlement (Pro/Business/Enterprise).
- Java 21 installed and available on PATH.
- Maven 3.9+ installed.
- `curl` installed.
- **Python 3** (optional) — for [`scripts/github-device-auth/copiproxy_dev_start.py`](../scripts/github-device-auth/copiproxy_dev_start.py), which automates startup and device login.

Verify:

```bash
java -version
mvn -version
curl --version
python3 --version   # optional
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
- `COPIPROXY_DEFAULT_MODEL` (default `claude-opus-4-6`) -- model injected when the client omits `model` from the request body
- `LOG_LEVEL` (default `INFO`)
- `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://localhost:4318/v1/traces`)

Example:

```bash
export PORT=3000
export COPROXY_STORAGE_SQLITE_PATH=./copiproxy.db
# or: export DATABASE_PATH=./copiproxy.db
export COPIPROXY_DEFAULT_MODEL=claude-opus-4-6
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

or **automated start + auth** (Python 3, stdlib only — no `pip`):

```bash
python3 scripts/github-device-auth/copiproxy_dev_start.py
```

This runs Maven in the foreground, waits until `GET /health` returns `ok`, checks whether the **default** API key in SQLite still works (`POST /admin/api-keys/{id}/refresh-meta`), and only then runs GitHub **device flow** if needed. It registers the new OAuth token via `POST /admin/api-keys` and sets it as default with `POST /admin/api-keys/default` (unlike the SSE device-flow endpoint alone, which does not set default).

If the app is already running:

```bash
python3 scripts/github-device-auth/copiproxy_dev_start.py --skip-start --base-url http://127.0.0.1:3000
```

Useful flags: `--force-auth` (always run device flow), `--no-browser` / `--no-clipboard`. Proxy and TLS: set `http_proxy` / `https_proxy` / `no_proxy` and `SSL_CERT_FILE` as for other tools; see [CORPORATE_PROXY.md](CORPORATE_PROXY.md).

Run unit tests for the script:

```bash
python3 -m unittest discover -s scripts/github-device-auth -p 'test_*.py' -v
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
  -d '{"id":"c0036b19-c253-4154-b7ad-903478a7270d"}'
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

### Option C: Same flow via Python helper

The script in `scripts/github-device-auth/copiproxy_dev_start.py` performs an equivalent OAuth device exchange against GitHub (aligned with the Java client), then creates the key and **sets default** in one go. Prefer this if you want a single command for “start + login when needed”; see **§4** above.

## 7) Test endpoints

### List models

```bash
curl http://localhost:3000/v1/models
```

This returns the live catalog from Copilot, including Claude Sonnet/Opus and GPT models your subscription has access to.

### Messages (non-streaming)

```bash
curl -X POST http://localhost:3000/v1/messages \
  -H 'content-type: application/json' \
  -H 'x-api-key: _' \
  -d '{"model":"claude-opus-4-6","max_tokens":100,"messages":[{"role":"user","content":"Say hello"}]}'
```

Omit `model` and CopiProxy injects the configured default (`claude-opus-4-6` unless you changed `COPIPROXY_DEFAULT_MODEL`).

### Messages (streaming)

Same request with `"stream": true`:

```bash
curl -X POST http://localhost:3000/v1/messages \
  -H 'content-type: application/json' \
  -H 'x-api-key: _' \
  -d '{"model":"claude-opus-4-6","max_tokens":100,"stream":true,"messages":[{"role":"user","content":"Say hello"}]}'
```

### Messages with explicit key override

```bash
curl -X POST http://localhost:3000/v1/messages \
  -H 'content-type: application/json' \
  -H 'x-api-key: YOUR_KEY' \
  -d '{"model":"claude-opus-4-6","max_tokens":100,"messages":[{"role":"user","content":"Say hello"}]}'
```

Available Claude model IDs: `claude-opus-4-6`, `claude-opus-4`, `claude-sonnet-4.6`, `claude-sonnet-4.5`, `claude-sonnet-4-20250514`.
GPT models (e.g. `gpt-4o`, `gpt-4.1`) also work. See [USING_COPILOT_MODELS.md](USING_COPILOT_MODELS.md) for full details.

### Dummy token for tools that require non-empty API key

Some clients require an API key value even when using a default server key.
Use `_`:

```bash
-H 'x-api-key: _'
```

CopiProxy interprets `_` as "use configured default key".

## 8) Configure Claude Code

Point Claude Code at the local proxy and set default model IDs to ones your Copilot catalog exposes:

```bash
export ANTHROPIC_BASE_URL="http://localhost:3000"
export ANTHROPIC_API_KEY="_"
export ANTHROPIC_DEFAULT_SONNET_MODEL="claude-sonnet-4.5"
export ANTHROPIC_DEFAULT_OPUS_MODEL="claude-opus-4-6"
export ANTHROPIC_DEFAULT_HAIKU_MODEL="claude-sonnet-4.5"
claude
```

Use your real GitHub key in `ANTHROPIC_API_KEY` if you are not relying on a default key configured in CopiProxy.

## 9) OpenTelemetry / monitoring

Available endpoints:

- `GET /actuator/health`
- `GET /actuator/metrics`
- `GET /actuator/prometheus`

Tracing is exported to `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://localhost:4318/v1/traces`).

If no OTLP collector is running, proxy still functions; trace export attempts may log warnings.

## 10) Troubleshooting

- **401 / missing API key**: ensure you set a default key in CopiProxy or pass `x-api-key: <key>` (or `x-api-key: _` to use the configured default).
- **400 model errors**: check model availability under your GitHub Copilot account settings and verify with `GET /v1/models`.
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
