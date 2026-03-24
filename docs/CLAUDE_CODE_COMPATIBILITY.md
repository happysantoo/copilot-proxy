# CopiProxy and Claude Code — compatibility and gaps

This document explains whether you can point **Anthropic Claude Code** (the `claude` CLI and related tooling) at **CopiProxy**, what would have to be true for that to work, and what is missing today.

---

## Short answer

**No — not with the current CopiProxy as-is.**  

CopiProxy is a **GitHub Copilot** backend proxy that exposes a small **OpenAI Chat Completions–shaped** surface (`/api/chat/completions`, `/api/models`). Claude Code is built to speak the **Anthropic Messages API** (`/v1/messages`, `anthropic-version`, `x-api-key`, Anthropic tool and streaming formats). Those are different protocols, paths, headers, and (often) model identifiers.

You could **in principle** add a **separate translation layer** (or extend the proxy) to adapt Claude Code’s Anthropic requests into Copilot’s OpenAI-style requests and map responses back. That is **not implemented** in this repository today.

---

## What CopiProxy does today

From the codebase and `README.md`:

| Aspect | Behavior |
|--------|----------|
| **Upstream** | `https://api.githubcopilot.com` (GitHub Copilot API) |
| **Auth to Copilot** | Runtime token from GitHub (`copilot_internal/v2/token`), resolved from your stored GitHub PAT / device flow; see `GithubCopilotService` and `TokenResolverService`. |
| **Client → proxy auth** | `Authorization: Bearer …` where the bearer is your CopiProxy API key (or default key / `_`); see `ProxyService.copyHeaders`. |
| **Public HTTP API** | `POST /api/chat/completions`, `GET /api/models` (see `ProxyController`) |
| **Request forwarding** | Raw body and most headers are forwarded; for chat, `Authorization` is **replaced** with Copilot’s `Bearer <runtime-token>`. Copilot-specific headers are set (`editor-version`, `copilot-integration-id`, etc.). |
| **Streaming** | Upstream `Content-Type` and body are streamed through unchanged (`StreamingResponseBody` + `InputStream.transferTo`). |

There is **no** `/v1/messages`, **no** Anthropic request/response schema, and **no** handling of `x-api-key` or `anthropic-version` as first-class concepts for the Copilot upstream.

---

## What Claude Code expects

Per [Claude Code environment variables](https://code.claude.com/docs/en/env-vars) (and Anthropic’s public API docs):

| Aspect | Typical behavior |
|--------|------------------|
| **`ANTHROPIC_BASE_URL`** | Overrides the API host used for model calls (e.g. corporate gateway, compatible proxy). |
| **Auth** | `ANTHROPIC_API_KEY` → **`X-Api-Key`** header, and/or `ANTHROPIC_AUTH_TOKEN` → **`Authorization: Bearer …`**. |
| **Primary HTTP API** | **Anthropic Messages API**, e.g. **`POST /v1/messages`**, not OpenAI’s `POST /v1/chat/completions`. |
| **Versioning** | Requests include **`anthropic-version`** (e.g. `2023-06-01`). |
| **Payload shape** | Anthropic JSON: `model`, `max_tokens`, `messages` with **content blocks**, optional **`tools`**, **`tool_choice`**, **`system`**, beta headers, etc. |
| **Streaming** | Anthropic’s **SSE event stream** format (e.g. `event:` / `data:` conventions for Messages), not necessarily identical to OpenAI’s chat completion stream. |
| **Models** | Claude model IDs (e.g. Sonnet/Opus/Haiku family names). CopiProxy’s `/api/models` reflects **Copilot’s** catalog (GPT-style and Copilot-specific IDs), not Anthropic’s. |

Claude Code also has options like **`CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS`** when a gateway rejects `anthropic-beta` headers — which underscores that it emits **Anthropic-specific** headers and fields that a pure OpenAI passthrough will not understand.

---

## Gap analysis (detailed)

### 1. URL layout and routing

- **Claude Code** (with a custom base URL) will call Anthropic-style paths such as **`/v1/messages`** (and possibly other `/v1/...` routes for counting, legacy APIs, or organization checks depending on version and mode).
- **CopiProxy** only defines **`/api/chat/completions`** and **`/api/models`**.

**Gap:** No route mapping from `/v1/messages` → Copilot. Even if you set `ANTHROPIC_BASE_URL=http://localhost:3000`, requests would **404** unless something else rewrites paths.

### 2. Protocol: Anthropic Messages vs OpenAI Chat Completions

- **Request:** Different JSON schema (e.g. `max_tokens` required on Anthropic side, content as string vs array of blocks, `system` handling, tool definitions).
- **Response:** Non-streaming and streaming shapes differ (OpenAI `choices[].message` vs Anthropic `content` blocks, `usage`, `stop_reason`, etc.).
- **Tools:** Claude Code relies on Anthropic **tool_use** / **tool_result** semantics. GitHub Copilot’s chat API may expose tools in an OpenAI-compatible way in some clients, but **adapting** Anthropic tool rounds to Copilot’s expected format is non-trivial and **not implemented** here.

**Gap:** A **bidirectional adapter** (Anthropic ⇄ OpenAI) would be required for Claude Code to talk to Copilot through this proxy.

### 3. Authentication semantics

- **CopiProxy** expects your **proxy API key** in `Authorization` and swaps in a **Copilot runtime JWT** for upstream (see `ProxyService`).
- **Claude Code** typically sends **`X-Api-Key`** (and possibly `Authorization` for subscription flows).

**Gap:** You would need either:

- To teach CopiProxy to accept **`X-Api-Key`** as the client credential (map it to the same resolution path as `Authorization`), **and**
- Ensure Claude Code does not require responses or parallel requests that CopiProxy does not implement.

Today, **only** the `Authorization` header path is wired for token resolution on `/chat/completions`.

### 4. Required Anthropic headers

Anthropic’s API expects **`anthropic-version`**. CopiProxy **forwards** unknown headers to Copilot, but **Copilot is not Anthropic** — it may ignore or reject unknown headers depending on the endpoint.

**Gap:** An adapter should **strip** Anthropic-only headers before calling Copilot and **re-inject** Anthropic-shaped headers on the way back to Claude Code (if you terminate TLS at the adapter).

### 5. Model identifiers

- Claude Code’s model picker and defaults assume **Claude** model names.
- Copilot’s `/models` returns **Copilot** models.

**Gap:** Even with a protocol adapter, you need a **model map** (e.g. treat `claude-sonnet-…` as a specific Copilot model ID) and accept that **capabilities will not match** (context length, vision, thinking, extended context, etc.).

### 6. Streaming and client behavior

CopiProxy streams the upstream body **as-is**. That is correct **only if** the upstream response is already in the format the **downstream client** expects.

**Gap:** Claude Code expects **Anthropic stream events**. Copilot returns **OpenAI-style** stream chunks. A **streaming translator** would be required in the middle.

### 7. Claude Code startup and “non-model” traffic

Reports and docs from the Claude Code ecosystem note that **interactive mode** may still perform **additional requests** (organization/status, telemetry, etc.) that **do not** go through the same paths as chat, or may ignore `ANTHROPIC_BASE_URL` in some cases. Mitigations often include variables such as **`CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC`** and related flags (see the official env var reference).

**Gap:** Operating behind a **partial** gateway is fragile; you may need **allowlists**, **split routing** (some traffic to Anthropic, chat-only to Copilot), or **full** compatibility shims.

### 8. Terms of use and product intent

GitHub Copilot’s service terms and GitHub’s policies govern **how Copilot may be used**. Routing third-party clients (or different UX surfaces) through a proxy can raise **compliance** questions separate from technical feasibility.

**Gap (non-technical):** Validate that your use case is allowed under **GitHub Copilot**, **Anthropic**, and your **employer** policies. This project does not provide legal guidance.

---

## What you can do instead (practical options)

### A. Use CopiProxy with OpenAI-compatible clients

Tools that support **`OPENAI_BASE_URL`** (or equivalent) and **`POST …/chat/completions`** are a much better fit: point the base URL at `http://<host>:<port>/api` **only if** the client concatenates paths correctly (many expect `/v1/chat/completions` — CopiProxy uses **`/api/chat/completions`**, which is another integration detail to verify).

### B. Use Claude Code with a real Anthropic-compatible endpoint

Set `ANTHROPIC_BASE_URL` to a gateway that implements **`/v1/messages`** (Anthropic or a verified-compatible layer), not to CopiProxy in its current form.

### C. Build (or deploy) an adapter service

A dedicated component could:

1. Terminate Claude Code’s Anthropic requests.
2. Map **`POST /v1/messages`** ↔ **`POST …/chat/completions`** on Copilot (including streaming).
3. Map model names and tool formats as far as Copilot allows.
4. Forward to CopiProxy **or** directly to `api.githubcopilot.com` with Copilot auth.

That adapter is **out of scope** for the current CopiProxy codebase but is the realistic path if the product goal is “Claude Code UI with Copilot quota.”

---

## Summary table

| Requirement for “Claude Code → CopiProxy” | Status in CopiProxy today |
|-------------------------------------------|---------------------------|
| `POST /v1/messages` (Anthropic) | **Not present** |
| Anthropic request/response mapping | **Not implemented** |
| Anthropic SSE ↔ OpenAI SSE | **Not implemented** |
| `X-Api-Key` as client credential | **Not implemented** (Bearer-focused) |
| Copilot runtime auth / API keys / SQLite | **Implemented** |
| OpenAI-style `POST /api/chat/completions` | **Implemented** |
| `GET /api/models` | **Implemented** |

---

## Conclusion

**You cannot reliably drive Claude Code through CopiProxy today** because Claude Code speaks the **Anthropic Messages API**, while CopiProxy exposes a **narrow OpenAI Chat Completions proxy** to **GitHub Copilot**. Closing the gap requires substantial **protocol, streaming, tool, routing, and auth** work—typically a **separate adapter**—plus careful attention to **model mapping** and **terms of use**.

For questions about this repository’s actual endpoints and behavior, see [`README.md`](../README.md) and [`LOCAL_SETUP.md`](LOCAL_SETUP.md).
