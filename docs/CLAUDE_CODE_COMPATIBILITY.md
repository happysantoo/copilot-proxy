# CopiProxy and Claude Code — Compatibility

CopiProxy now implements the **Anthropic Messages API** surface, making it compatible with **Claude Code** and other Anthropic clients.

---

## Setup

```bash
export ANTHROPIC_BASE_URL="http://localhost:3000"
export ANTHROPIC_API_KEY="_"
export ANTHROPIC_DEFAULT_SONNET_MODEL="claude-sonnet-4.6"
export ANTHROPIC_DEFAULT_OPUS_MODEL="claude-opus-4-6"
export ANTHROPIC_DEFAULT_HAIKU_MODEL="claude-sonnet-4.6"
claude
```

Set `ANTHROPIC_API_KEY` to `_` if you are using a CopiProxy default key, or to your stored CopiProxy API key if you created one via the admin endpoints.

---

## What works

| Feature | Status |
|---------|--------|
| `POST /v1/messages` | Fully translated (Anthropic → OpenAI → Copilot) |
| `GET /v1/models` | Pass-through to Copilot |
| Text conversations | Works |
| Streaming (SSE) | Translated from OpenAI SSE to Anthropic SSE events |
| Tool use / function calling | Bidirectional translation (tool definitions, tool_use, tool_result) |
| `x-api-key` authentication | Accepted and mapped to CopiProxy token resolution |
| `system` messages | Hoisted from top-level to system role |
| `stop_sequences` | Mapped to OpenAI `stop` |
| Default model injection | `claude-opus-4-6` injected when client omits `model` |
| Image content blocks | Base64 images translated to OpenAI `image_url` format |

---

## Known limitations

These Anthropic-specific features are **not available** through the Copilot upstream and are stripped during translation:

| Feature | Impact |
|---------|--------|
| Extended thinking (`thinking` blocks) | Medium — reasoning traces lost |
| Prompt caching (`cache_control`) | Low-Medium — increased latency without caching |
| Citations | Low — source attribution unavailable |
| PDF input | Low — binary PDF blocks dropped |
| `anthropic-beta` headers | Stripped before upstream; features behind betas unavailable |
| `metadata` field | Stripped |
| `top_k` sampling | Not supported by OpenAI API |

See [TRANSLATION_GAP_ANALYSIS.md](TRANSLATION_GAP_ANALYSIS.md) for the full gap analysis.

---

## Recommended environment variables

If Claude Code sends `anthropic-beta` headers that cause issues, disable them:

```bash
export CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS=1
```

To suppress non-essential telemetry traffic (which does not route through CopiProxy):

```bash
export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1
```

---

## Model mapping

Claude Code uses Anthropic model names. CopiProxy passes these directly to Copilot. Known working model IDs:

- `claude-opus-4-6` (default)
- `claude-sonnet-4.6`
- `claude-sonnet-4`
- `claude-haiku-3.5`

Use `GET /v1/models` to see the full list of models available through your Copilot subscription.

---

## Architecture

```
Claude Code → POST /v1/messages → CopiProxy
                                    │
                         Anthropic→OpenAI translation
                                    │
                         POST /chat/completions → api.githubcopilot.com
                                    │
                         OpenAI→Anthropic translation
                                    │
Claude Code ← Anthropic SSE/JSON ← CopiProxy
```

For detailed technical information, see:
- [`README.md`](../README.md) — project overview
- [`LOCAL_SETUP.md`](LOCAL_SETUP.md) — step-by-step setup
- [`TRANSLATION_GAP_ANALYSIS.md`](TRANSLATION_GAP_ANALYSIS.md) — detailed translation analysis
