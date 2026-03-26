# Claude Code Setup Guide

Use your GitHub Copilot subscription to power Claude Code through CopiProxy.

```
┌─────────────┐         ┌──────────────────┐         ┌──────────────────────────┐
│  claude CLI  │──POST──▶│  CopiProxy       │──POST──▶│  api.githubcopilot.com   │
│  (Anthropic  │  /v1/   │  localhost:3000   │  /chat/ │  (OpenAI-style upstream) │
│   Messages)  │◀─SSE────│  Anthropic↔OpenAI │◀─SSE────│                          │
└─────────────┘         └──────────────────┘         └──────────────────────────┘
```

CopiProxy accepts Anthropic Messages API requests from Claude Code, translates them
to the OpenAI format that GitHub Copilot expects, and translates responses back.

---

## 1. Prerequisites

| Requirement | Why |
|-------------|-----|
| GitHub account with **Copilot Pro**, Business, or Enterprise | Provides the upstream model quota |
| **Java 21** on PATH | CopiProxy runtime |
| **Maven 3.9+** | Build tool |
| **Claude Code CLI** | The client you want to run |

Verify everything is installed:

```bash
java -version          # 21.x.x
mvn -version           # 3.9+
claude --version       # any recent version
```

If you don't have Claude Code yet:

```bash
npm install -g @anthropic-ai/claude-code
```

---

## 2. Build CopiProxy

```bash
git clone https://github.com/happysantoo/copilot-proxy.git copiproxy
cd copiproxy
mvn clean package -DskipTests
```

A successful build produces `target/copiproxy-0.1.0.jar`.

---

## 3. Start the proxy

Pick one:

```bash
# Option A: run the jar directly
java -jar target/copiproxy-0.1.0.jar

# Option B: run via Maven
mvn spring-boot:run
```

The proxy starts on port **3000** by default. Override with `export PORT=<port>` before starting.

Verify it's running:

```bash
curl http://localhost:3000/health
```

Expected: `{"status":"ok"}`

---

## 4. Register your GitHub token

CopiProxy needs a GitHub token that can exchange for a Copilot runtime JWT. You have two options.

### Option A: Device flow (recommended)

This is the easiest method — it opens a GitHub login page in your browser.

**Step 1 — Start the flow:**

```bash
curl -N http://localhost:3000/admin/api-keys/device-flow
```

You'll receive Server-Sent Events. The first event looks like:

```
event:initiated
data:{"type":"initiated","deviceCode":"...","userCode":"ABCD-1234","verificationUri":"https://github.com/login/device","expiresAt":...,"message":"Device flow initiated. Please authorize in browser."}
```

**Step 2 — Authorize in your browser:**

1. Open the `verificationUri` URL (https://github.com/login/device).
2. Enter the `userCode` shown in the event (e.g. `ABCD-1234`).
3. Approve the request.

**Step 3 — Wait for success:**

The terminal will show `pending` events while waiting, then:

```
event:success
data:{"type":"success","message":"Authorization successful","apiKeyId":"<id>"}
```

**Step 4 — Set as default:**

Copy the `apiKeyId` from the success event and set it as the default key:

```bash
curl -X POST http://localhost:3000/admin/api-keys/default \
  -H 'content-type: application/json' \
  -d '{"id":"<apiKeyId>"}'
```

### Option B: Manual key add

If you already have a GitHub token (e.g. from `gh auth token`):

```bash
# Add the key
curl -X POST http://localhost:3000/admin/api-keys \
  -H 'content-type: application/json' \
  -d '{"name":"my-github-key","key":"<YOUR_GITHUB_TOKEN>"}'
```

Note the `id` in the response, then set it as default:

```bash
curl -X POST http://localhost:3000/admin/api-keys/default \
  -H 'content-type: application/json' \
  -d '{"id":"<id>"}'
```

---

## 5. Verify the proxy works

Before launching Claude Code, confirm the proxy can talk to Copilot.

### List available models

```bash
curl http://localhost:3000/v1/models
```

You should see a JSON array containing model IDs like `claude-opus-4-6`, `claude-sonnet-4.6`, `gpt-4o`, etc.

### Send a test message

```bash
curl -X POST http://localhost:3000/v1/messages \
  -H 'content-type: application/json' \
  -H 'x-api-key: _' \
  -d '{
    "model": "claude-opus-4-6",
    "max_tokens": 50,
    "messages": [{"role": "user", "content": "Say hello in one sentence."}]
  }'
```

Expected response shape:

```json
{
  "id": "msg_...",
  "type": "message",
  "role": "assistant",
  "model": "claude-opus-4-6",
  "content": [
    {"type": "text", "text": "Hello! How can I help you today?"}
  ],
  "stop_reason": "end_turn",
  "stop_sequence": null,
  "usage": {"input_tokens": 12, "output_tokens": 10}
}
```

If you get a valid response with `"type": "message"`, the proxy is working correctly.

---

## 6. Configure Claude Code

Set these environment variables to point Claude Code at your local proxy:

```bash
export ANTHROPIC_BASE_URL="http://localhost:3000"
export ANTHROPIC_API_KEY="_"
export ANTHROPIC_DEFAULT_SONNET_MODEL="claude-sonnet-4.6"
export ANTHROPIC_DEFAULT_OPUS_MODEL="claude-opus-4-6"
export ANTHROPIC_DEFAULT_HAIKU_MODEL="claude-sonnet-4.6"
```

Then launch Claude Code:

```bash
claude
```

### What these variables do

| Variable | Value | Purpose |
|----------|-------|---------|
| `ANTHROPIC_BASE_URL` | `http://localhost:3000` | Redirects all API calls to CopiProxy instead of `api.anthropic.com` |
| `ANTHROPIC_API_KEY` | `_` | Dummy token. CopiProxy sees `_` and uses your default registered key. If you have multiple keys, set this to the actual key value instead. |
| `ANTHROPIC_DEFAULT_SONNET_MODEL` | `claude-sonnet-4.6` | Model used when Claude Code requests Sonnet |
| `ANTHROPIC_DEFAULT_OPUS_MODEL` | `claude-opus-4-6` | Model used when Claude Code requests Opus |
| `ANTHROPIC_DEFAULT_HAIKU_MODEL` | `claude-sonnet-4.6` | Model used when Claude Code requests Haiku (mapped to Sonnet since Copilot may not expose Haiku) |

### Recommended optional variables

Suppress non-essential traffic that Claude Code sends directly to Anthropic (telemetry, etc.) — this traffic does not go through CopiProxy and would fail or be unnecessary:

```bash
export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1
```

If you encounter errors related to beta feature headers:

```bash
export CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS=1
```

---

## 7. Available models

Models available depend on your Copilot subscription tier. Known working IDs:

| Model ID | Family | Notes |
|----------|--------|-------|
| `claude-opus-4-6` | Opus | Default model; highest capability |
| `claude-opus-4` | Opus | May resolve to same as above |
| `claude-sonnet-4.6` | Sonnet | Fast, cost-effective |
| `claude-sonnet-4` | Sonnet | Previous generation |
| `claude-haiku-3.5` | Haiku | Fastest |
| `gpt-4o` | GPT | Also available through Copilot |
| `gpt-4.1` | GPT | Also available through Copilot |

Check what your subscription exposes:

```bash
curl http://localhost:3000/v1/models
```

To change which model Claude Code uses by default, adjust the `ANTHROPIC_DEFAULT_*_MODEL` env vars. CopiProxy also injects `claude-opus-4-6` when a request arrives without a model specified; override this with:

```bash
export COPIPROXY_DEFAULT_MODEL=claude-sonnet-4.6
```

Set this before starting CopiProxy.

---

## 8. Shell script for daily use

Save this as `start-claude-code.sh` in the project root for a one-command launch:

```bash
#!/usr/bin/env bash
set -euo pipefail

PROXY_DIR="$(cd "$(dirname "$0")" && pwd)"
PROXY_JAR="$PROXY_DIR/target/copiproxy-0.1.0.jar"
PROXY_PORT="${PORT:-3000}"
PROXY_PID=""

cleanup() {
  if [ -n "$PROXY_PID" ] && kill -0 "$PROXY_PID" 2>/dev/null; then
    echo "Stopping CopiProxy (PID $PROXY_PID)..."
    kill "$PROXY_PID"
    wait "$PROXY_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

if [ ! -f "$PROXY_JAR" ]; then
  echo "Building CopiProxy..."
  (cd "$PROXY_DIR" && mvn clean package -DskipTests -q)
fi

echo "Starting CopiProxy on port $PROXY_PORT..."
java -jar "$PROXY_JAR" &
PROXY_PID=$!

echo "Waiting for proxy to become healthy..."
for i in $(seq 1 30); do
  if curl -sf "http://localhost:$PROXY_PORT/health" >/dev/null 2>&1; then
    echo "CopiProxy is ready."
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "ERROR: CopiProxy did not start within 30 seconds."
    exit 1
  fi
  sleep 1
done

export ANTHROPIC_BASE_URL="http://localhost:$PROXY_PORT"
export ANTHROPIC_API_KEY="_"
export ANTHROPIC_DEFAULT_SONNET_MODEL="claude-sonnet-4.6"
export ANTHROPIC_DEFAULT_OPUS_MODEL="claude-opus-4-6"
export ANTHROPIC_DEFAULT_HAIKU_MODEL="claude-sonnet-4.6"
export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1

echo "Launching Claude Code..."
claude "$@"
```

Make it executable and run:

```bash
chmod +x start-claude-code.sh
./start-claude-code.sh
```

The script starts CopiProxy in the background, waits for it to be healthy, configures the environment, launches `claude`, and stops the proxy when you exit Claude Code.

---

## 9. Troubleshooting

### "Connection refused" when launching claude

CopiProxy is not running or is on a different port.

```bash
curl http://localhost:3000/health
```

If this fails, start the proxy first (step 3). If you changed the port, make sure `ANTHROPIC_BASE_URL` matches.

### 401 / authentication error

Your GitHub token is missing, expired, or not set as the default key.

1. Check stored keys: `curl http://localhost:3000/admin/api-keys`
2. Refresh metadata: `curl -X POST http://localhost:3000/admin/api-keys/<id>/refresh-meta`
3. If expired, re-run the device flow (step 4, Option A).

For deeper analysis (temporary runtime expiry vs dead/revoked GitHub token), see [TOKEN_REFRESH_AND_AUTH_DEBUGGING.md](TOKEN_REFRESH_AND_AUTH_DEBUGGING.md).

### "model not found" or 404

The model ID is not available under your Copilot subscription.

```bash
curl http://localhost:3000/v1/models
```

Verify the model you are requesting appears in the list. Adjust `ANTHROPIC_DEFAULT_*_MODEL` to a model that is available.

### 429 rate limited

Copilot Pro has per-model rate limits (varies by tier). Wait and retry. Consider switching to a lower-tier model for high-volume use:

```bash
export ANTHROPIC_DEFAULT_SONNET_MODEL="claude-sonnet-4"
```

### "Cannot reach the network" behind a corporate proxy

If your machine uses a corporate proxy (`http_proxy` / `https_proxy` env vars), Claude Code will try to route **all** traffic through it — including connections to `localhost` where CopiProxy runs. The corporate proxy cannot reach your local machine, so the connection fails.

Add `localhost` and `127.0.0.1` to `no_proxy` so that Claude Code connects to CopiProxy directly:

```bash
export no_proxy="${no_proxy:+$no_proxy,}localhost,127.0.0.1"
```

CopiProxy itself already reads `https_proxy` / `http_proxy` and routes its upstream GitHub API calls through the corporate proxy automatically (see [CORPORATE_PROXY.md](CORPORATE_PROXY.md)).

### Claude Code hangs on startup

Claude Code may try to reach `api.anthropic.com` for non-chat traffic. Set:

```bash
export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1
export CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS=1
```

### Port conflict (address already in use)

Another process is using port 3000. Either stop it or change the proxy port:

```bash
export PORT=3100
export ANTHROPIC_BASE_URL="http://localhost:3100"
```

### Proxy starts but curl returns garbled output

Make sure you are hitting `/v1/messages` (Anthropic format), not the old `/api/chat/completions`. The proxy only speaks Anthropic Messages API on the client side.

### Database / key issues

For a clean slate, remove the SQLite database and re-register your key:

```bash
rm copiproxy.db
```

---

## 10. Known limitations

CopiProxy translates between the Anthropic Messages API and the OpenAI Chat Completions API that Copilot uses. Some Anthropic-specific features are unavailable:

| Feature | Impact |
|---------|--------|
| Extended thinking (`thinking` blocks) | Reasoning traces are not returned |
| Prompt caching (`cache_control`) | Every request is a full round-trip; no caching benefit |
| Citations | Source attribution unavailable |
| PDF input | Binary PDF content blocks are dropped |
| `anthropic-beta` headers | Stripped before reaching Copilot; beta features won't activate |
| `top_k` sampling | Not supported by the OpenAI API |

Core functionality — text conversations, streaming, tool use / function calling, image input, system prompts — works fully.

For a comprehensive analysis, see [TRANSLATION_GAP_ANALYSIS.md](TRANSLATION_GAP_ANALYSIS.md).

---

## Quick reference

```bash
# Start proxy
java -jar target/copiproxy-0.1.0.jar &

# Configure Claude Code
export ANTHROPIC_BASE_URL="http://localhost:3000"
export ANTHROPIC_API_KEY="_"
export ANTHROPIC_DEFAULT_SONNET_MODEL="claude-sonnet-4.6"
export ANTHROPIC_DEFAULT_OPUS_MODEL="claude-opus-4-6"
export ANTHROPIC_DEFAULT_HAIKU_MODEL="claude-sonnet-4.6"
export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1

# Launch
claude
```
