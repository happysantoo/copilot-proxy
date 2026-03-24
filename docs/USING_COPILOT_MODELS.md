# Using Claude Sonnet and Opus models via Copilot Pro

CopiProxy can route requests to Claude Sonnet and Opus models that are available through your GitHub Copilot Pro (or Pro+/Business/Enterprise) subscription. The proxy exposes the **Anthropic Messages API** (`POST /v1/messages`): clients send Anthropic-shaped JSON and headers; CopiProxy translates to GitHub Copilot’s chat completion API and maps responses (including streaming) back to Anthropic format.

## Prerequisites

1. A **GitHub Copilot Pro** (or higher) subscription with Claude models enabled.
2. A GitHub PAT or device-flow token registered in CopiProxy (see [LOCAL_SETUP.md](LOCAL_SETUP.md)).
3. CopiProxy running (`mvn spring-boot:run` or the built jar).

## Available Claude model IDs

These are the model strings you pass in the `model` field of a Messages request. The authoritative list comes from `GET /v1/models` (which queries Copilot upstream), but these are the known Claude IDs as of March 2026:

| Model name | `model` value | Premium multiplier | Notes |
|---|---|---|---|
| Claude Opus 4.6 | `claude-opus-4-6` | High (varies) | **CopiProxy default** |
| Claude Sonnet 4.6 | `claude-sonnet-4.6` | 1x | GA since Feb 2026 |
| Claude Sonnet 4.5 | `claude-sonnet-4.5` | 1x | |
| Claude Sonnet 4 | `claude-sonnet-4-20250514` | 1x | |

Older catalogs may also list `claude-3.5-sonnet` or legacy Opus aliases (e.g. `claude-opus-4`). GPT-family models (e.g. `gpt-4o`, `gpt-4.1`) continue to work when Copilot exposes them.

## Default model

CopiProxy injects a **default model** when the client’s request body does not contain a `model` field (or the field is null/blank). Out of the box this is **`claude-opus-4-6`**.

Override in `application.yml`:

```yaml
copiproxy:
  github:
    default-model: claude-sonnet-4.6   # or any valid Copilot model ID
```

Or via environment variable:

```bash
export COPIPROXY_DEFAULT_MODEL=claude-sonnet-4.6
mvn spring-boot:run
```

When a client **does** send a `model` value, CopiProxy forwards it as-is to Copilot (after translation).

## Client authentication

Anthropic-compatible clients typically send your CopiProxy API key in **`x-api-key`**. CopiProxy resolves that key the same way as **`Authorization: Bearer …`** (including the dummy value `_` when a default key is configured). Headers such as **`anthropic-version`** and **`anthropic-beta`** are accepted from the client but **stripped** before the request is sent to GitHub Copilot.

## Example requests

Replace `<YOUR_COPIPROXY_KEY>` with your registered key, or use a placeholder like `_` when a default key is set (see [LOCAL_SETUP.md](LOCAL_SETUP.md)).

### Explicit model selection (Sonnet 4.6)

```bash
curl -X POST http://localhost:3000/v1/messages \
  -H 'content-type: application/json' \
  -H 'x-api-key: <YOUR_COPIPROXY_KEY>' \
  -H 'anthropic-version: 2023-06-01' \
  -d '{
    "model": "claude-sonnet-4.6",
    "max_tokens": 1024,
    "messages": [
      {"role": "user", "content": "Explain virtual threads in Java 21"}
    ]
  }'
```

### Using the default model (Opus 4.6)

Omit `model` and CopiProxy injects the configured default:

```bash
curl -X POST http://localhost:3000/v1/messages \
  -H 'content-type: application/json' \
  -H 'x-api-key: <YOUR_COPIPROXY_KEY>' \
  -H 'anthropic-version: 2023-06-01' \
  -d '{
    "max_tokens": 256,
    "messages": [
      {"role": "user", "content": "Write a haiku about proxies"}
    ]
  }'
```

If you omit `max_tokens`, CopiProxy supplies **4096** when calling Copilot (Anthropic’s API normally requires `max_tokens`; the translator defaults it for convenience).

### Streaming

Set `"stream": true` in the JSON body. The response is **Anthropic-style** Server-Sent Events (`text/event-stream`), not OpenAI chat completion chunks.

### List available models

```bash
curl http://localhost:3000/v1/models \
  -H 'x-api-key: <YOUR_COPIPROXY_KEY>'
```

This returns the live catalog from Copilot, including Claude and GPT models your subscription has access to. (The payload shape follows Copilot’s `/models` response.)

## Claude Code

**Claude Code** (`claude` CLI and related tooling) can use CopiProxy directly by pointing it at the proxy’s origin and supplying your CopiProxy key.

1. Start CopiProxy and ensure a GitHub-backed key is registered (device flow or PAT), with an optional default key if you plan to use `_`.
2. Set environment variables (shell profile or project `.env`, depending on how you launch Claude Code):

   ```bash
   export ANTHROPIC_BASE_URL=http://localhost:3000
   export ANTHROPIC_API_KEY=<YOUR_COPIPROXY_KEY>
   ```

   If your setup uses a default CopiProxy key and the CLI requires a non-empty key, you can set `ANTHROPIC_API_KEY=_` (same convention as [LOCAL_SETUP.md](LOCAL_SETUP.md)).

3. Claude Code will call **`POST /v1/messages`** (and **`GET /v1/models`** where needed) on that base URL.

If the CLI sends beta headers your Copilot path rejects, see Claude Code’s env documentation for flags such as disabling non-essential traffic or experimental betas. For protocol details and historical limitations, [CLAUDE_CODE_COMPATIBILITY.md](CLAUDE_CODE_COMPATIBILITY.md) may still be useful context.

## Upstream (Copilot) header configuration

CopiProxy injects headers that match a real VS Code Copilot Chat session when talking to `api.githubcopilot.com`. You do **not** send these from curl as OpenAI client headers; they are applied server-side. Optional overrides in `application.yml`:

```yaml
copiproxy:
  github:
    editor-version: vscode/1.98.0
    editor-plugin-version: copilot-chat/0.23.2
    user-agent: GitHubCopilotChat/0.23.2
    copilot-integration-id: vscode-chat
    openai-organization: github-copilot
    openai-intent: conversation-panel
    github-api-version: 2025-01-21
```

These apply to proxied traffic (e.g. chat completions and models upstream).

## Rate limits and premium requests

Copilot Pro plans have a monthly premium request allowance. Each model has a **multiplier** that determines how many premium requests a single API call consumes:

- **Claude Opus 4.6** (`claude-opus-4-6`): High multiplier (consumes more premium requests per call).
- **Claude Sonnet 4.6** (`claude-sonnet-4.6`): 1x multiplier (consumes the same as a standard request).

When your quota is exhausted, Copilot returns **HTTP 429 Too Many Requests**. CopiProxy translates errors to Anthropic-style JSON when possible and logs a warning. There is no built-in retry.

To check your remaining quota, use the GitHub Copilot dashboard or the metadata from `GET /v1/models` when available.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `GET /v1/models` returns 401 | Token not configured or expired | Run device flow or add a PAT via `/admin/api-keys`; ensure `x-api-key` or `Authorization` is set |
| Claude models not in `/v1/models` response | Copilot plan doesn't include Claude, or admin hasn't enabled the policy | Check your Copilot subscription settings on GitHub |
| 429 Too Many Requests | Premium request quota exhausted | Wait for quota reset, or switch to a model with a lower multiplier (e.g. Sonnet) |
| Model not found / 400 | Wrong model ID string | Use the exact IDs from the table above, or from `GET /v1/models` |
