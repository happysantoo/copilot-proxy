# Using Claude Sonnet and Opus models via Copilot Pro

CopiProxy can route requests to Claude Sonnet and Opus models that are available through your GitHub Copilot Pro (or Pro+/Business/Enterprise) subscription. These models are served by `api.githubcopilot.com` using the same OpenAI Chat Completions format -- no client-side changes are needed beyond selecting the right model ID.

## Prerequisites

1. A **GitHub Copilot Pro** (or higher) subscription with Claude models enabled.
2. A GitHub PAT or device-flow token registered in CopiProxy (see [LOCAL_SETUP.md](LOCAL_SETUP.md)).
3. CopiProxy running (`mvn spring-boot:run` or the built jar).

## Available Claude model IDs

These are the model strings you pass in the `model` field of a chat completion request. The authoritative list comes from `GET /api/models` (which queries Copilot upstream), but these are the known Claude IDs as of March 2026:

| Model name | `model` value | Premium multiplier | Notes |
|---|---|---|---|
| Claude Opus 4.6 | `claude-opus-4` | High (varies) | **CopiProxy default** |
| Claude Sonnet 4.6 | `claude-sonnet-4.6` | 1x | GA since Feb 2026 |
| Claude Sonnet 4.5 | `claude-sonnet-4.5` | 1x | |
| Claude Sonnet 4 | `claude-sonnet-4-20250514` | 1x | |

Older catalogs may also list `claude-3.5-sonnet`. GPT-family models (e.g. `gpt-4o`, `gpt-4.1`) continue to work as before.

## Default model

CopiProxy injects a **default model** when the client's request body does not contain a `model` field (or the field is null/blank). Out of the box this is **`claude-opus-4`**.

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

When a client **does** send a `model` value, CopiProxy forwards it as-is.

## Example requests

### Explicit model selection (Sonnet 4.6)

```bash
curl -X POST http://localhost:3000/api/chat/completions \
  -H 'content-type: application/json' \
  -d '{
    "model": "claude-sonnet-4.6",
    "messages": [{"role": "user", "content": "Explain virtual threads in Java 21"}]
  }'
```

### Using the default model (Opus 4.6)

Omit `model` and CopiProxy injects the configured default:

```bash
curl -X POST http://localhost:3000/api/chat/completions \
  -H 'content-type: application/json' \
  -d '{
    "messages": [{"role": "user", "content": "Write a haiku about proxies"}]
  }'
```

### List available models

```bash
curl http://localhost:3000/api/models
```

This returns the live catalog from Copilot, including all Claude and GPT models your subscription has access to.

## Header configuration

CopiProxy sends headers that match a real VS Code Copilot Chat session. The defaults work out of the box, but you can override them in `application.yml` if needed:

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

These are sent on **every** proxied request (both `/chat/completions` and `/models`).

## Rate limits and premium requests

Copilot Pro plans have a monthly premium request allowance. Each model has a **multiplier** that determines how many premium requests a single API call consumes:

- **Claude Opus 4.6** (`claude-opus-4`): High multiplier (consumes more premium requests per call).
- **Claude Sonnet 4.6** (`claude-sonnet-4.6`): 1x multiplier (consumes the same as a standard request).

When your quota is exhausted, Copilot returns **HTTP 429 Too Many Requests**. CopiProxy forwards this status to the client and logs a warning. There is no built-in retry.

To check your remaining quota, use the GitHub Copilot dashboard or the `GET /api/models` response metadata.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `GET /api/models` returns 401 | Token not configured or expired | Run device flow or add a PAT via `/admin/api-keys` |
| Claude models not in `/api/models` response | Copilot plan doesn't include Claude, or admin hasn't enabled the policy | Check your Copilot subscription settings on GitHub |
| 429 Too Many Requests | Premium request quota exhausted | Wait for quota reset, or switch to a model with a lower multiplier (e.g. Sonnet) |
| Model not found / 400 | Wrong model ID string | Use the exact IDs from the table above, or from `GET /api/models` |
