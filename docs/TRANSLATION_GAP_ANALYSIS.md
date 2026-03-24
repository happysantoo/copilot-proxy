# Anthropic ↔ OpenAI Translation Gap Analysis

CopiProxy will accept **Anthropic Messages API** requests from Claude Code and translate them into **OpenAI Chat Completions** format for the GitHub Copilot upstream (`api.githubcopilot.com/chat/completions`). Responses travel the reverse path.

This document catalogues every semantic gap in that translation — what maps cleanly, what is lost, and what the impact is on Claude Code.

---

## 1. Fields that translate cleanly (1:1 mapping)

These have direct equivalents and require only structural transformation:

| Anthropic (request) | OpenAI (request) | Notes |
|---|---|---|
| `model` | `model` | Copilot understands Claude model IDs (`claude-opus-4-6`, `claude-sonnet-4.6`, etc.) |
| `max_tokens` (required) | `max_tokens` | Semantically identical |
| `temperature` | `temperature` | Anthropic caps at 1.0; OpenAI allows up to 2.0 |
| `top_p` | `top_p` | Identical |
| `stream` | `stream` | Identical |
| `stop_sequences` | `stop` | Array of strings in both |
| `system` (top-level string) | Prepend `{role: "system", content: ...}` message | Anthropic has a dedicated field; OpenAI uses a system-role message |

| Anthropic (response) | OpenAI (response) | Notes |
|---|---|---|
| `content[].text` | `choices[0].message.content` | Text content |
| `stop_reason: "end_turn"` | `finish_reason: "stop"` | Normal completion |
| `stop_reason: "tool_use"` | `finish_reason: "tool_calls"` | Tool invocation |
| `stop_reason: "max_tokens"` | `finish_reason: "length"` | Token limit hit |
| `usage.input_tokens` | `usage.prompt_tokens` | Input token count |
| `usage.output_tokens` | `usage.completion_tokens` | Output token count |
| `id` (e.g. `msg_...`) | `id` (e.g. `chatcmpl-...`) | We generate a synthetic `msg_` prefixed ID |
| `model` | `model` | Pass through from response |

---

## 2. Message format translation

### 2.1 User messages

| Anthropic format | OpenAI format | Gap? |
|---|---|---|
| `{role: "user", content: "text"}` | `{role: "user", content: "text"}` | None |
| `{role: "user", content: [{type: "text", text: "..."}]}` | `{role: "user", content: [{type: "text", text: "..."}]}` | None — both support content block arrays |
| `{role: "user", content: [{type: "image", source: {type: "base64", media_type, data}}]}` | `{role: "user", content: [{type: "image_url", image_url: {url: "data:<media_type>;base64,<data>"}}]}` | **Translatable** — different structure for the same data |
| `{role: "user", content: [{type: "tool_result", tool_use_id, content}]}` | `{role: "tool", tool_call_id, content}` | **Structural change** — Anthropic nests tool results inside a user message; OpenAI uses a separate `role: "tool"` message |

**Mixed user messages** (text + tool_result blocks in one Anthropic message) must be **split** into multiple OpenAI messages: one `role: "tool"` per tool_result, then a `role: "user"` for the text.

### 2.2 Assistant messages

| Anthropic format | OpenAI format | Gap? |
|---|---|---|
| `{role: "assistant", content: [{type: "text", text: "..."}]}` | `{role: "assistant", content: "..."}` | None |
| `{role: "assistant", content: [{type: "tool_use", id, name, input: {...}}]}` | `{role: "assistant", tool_calls: [{id, type: "function", function: {name, arguments: JSON.stringify(input)}}]}` | **Translatable** — `input` (object) ↔ `arguments` (JSON string) |
| Mixed text + tool_use blocks | `content: "text"` + `tool_calls: [...]` | **Translatable** — split into two fields |

### 2.3 System messages

Anthropic uses a **top-level `system` field** (string or array of content blocks). OpenAI uses `{role: "system", content: "..."}` messages inline.

**Translation:** Extract `system` field and prepend as the first message with `role: "system"`. On the reverse path, if the first message is `role: "system"`, hoist it to the top-level `system` field. This is a clean mapping.

---

## 3. Tool definitions

| Anthropic | OpenAI | Gap? |
|---|---|---|
| `{name, description, input_schema: {...}}` | `{type: "function", function: {name, description, parameters: {...}}}` | **Translatable** — `input_schema` ↔ `parameters`, wrapped in `type: "function"` envelope |

### 3.1 tool_choice mapping

| Anthropic `tool_choice` | OpenAI `tool_choice` |
|---|---|
| `{type: "auto"}` | `"auto"` |
| `{type: "any"}` | `"required"` |
| `{type: "none"}` | `"none"` |
| `{type: "tool", name: "xyz"}` | `{type: "function", function: {name: "xyz"}}` |

All values translate without loss.

### 3.2 Tool calling features with gaps

| Feature | Anthropic | OpenAI | Impact |
|---|---|---|---|
| `strict` schema validation | Not applicable (Anthropic has its own structured outputs) | `strict: true` on tool definitions | **No impact** — Copilot ignores `strict` anyway |
| `disable_parallel_tool_use` | Field on `tool_choice` | `parallel_tool_calls: false` (inverted boolean) | **Translatable** with inversion |
| `input_examples` | Optional field on tool definitions | No equivalent | **Stripped** — minor quality hint lost |

---

## 4. Streaming format translation

This is the most complex translation because the SSE event shapes differ fundamentally.

### 4.1 OpenAI streaming format (what Copilot returns)

```
data: {"id":"chatcmpl-xxx","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}
data: {"id":"chatcmpl-xxx","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}
data: {"id":"chatcmpl-xxx","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}
data: {"id":"chatcmpl-xxx","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{...}}
data: [DONE]
```

- Flat `data:` lines, no named `event:` field
- Content in `choices[0].delta.content`
- Tool calls in `choices[0].delta.tool_calls[n].function.arguments` (incremental JSON string)
- Ends with `data: [DONE]`

### 4.2 Anthropic streaming format (what Claude Code expects)

```
event: message_start
data: {"type":"message_start","message":{"id":"msg_xxx","type":"message","role":"assistant","content":[],"model":"claude-opus-4-6","stop_reason":null,"usage":{"input_tokens":25,"output_tokens":1}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"!"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":15}}

event: message_stop
data: {"type":"message_stop"}
```

- Named `event:` + `data:` pairs
- Typed lifecycle: `message_start` → `content_block_start` → `content_block_delta`* → `content_block_stop` → `message_delta` → `message_stop`
- Tool calls use `content_block_start` with `{type: "tool_use", id, name}` and `content_block_delta` with `{type: "input_json_delta", partial_json: "..."}`

### 4.3 Translation strategy

A stateful `StreamTranslator` reads OpenAI SSE lines and emits Anthropic SSE events:

| OpenAI event | Anthropic event(s) emitted |
|---|---|
| First chunk (with `delta.role`) | `message_start` (synthetic message shell) |
| First `delta.content` | `content_block_start` (text) + `content_block_delta` |
| Subsequent `delta.content` | `content_block_delta` |
| First `delta.tool_calls[n]` (with `id`, `function.name`) | Close any open text block (`content_block_stop`) + `content_block_start` (tool_use) |
| Subsequent `delta.tool_calls[n].function.arguments` | `content_block_delta` (input_json_delta) |
| `finish_reason` present | `content_block_stop` + `message_delta` + `message_stop` |
| `data: [DONE]` | Ensure all blocks closed; emit `message_stop` if not yet sent |

### 4.4 Streaming gaps

| Gap | Impact |
|---|---|
| **`ping` events** — Anthropic periodically sends `event: ping`. OpenAI has no equivalent. | We can synthesize `ping` events on a timer or skip them. Claude Code handles their absence gracefully. |
| **`usage` in `message_start`** — Anthropic includes `input_tokens` in the first event. OpenAI may not send usage until the final chunk. | We set `input_tokens: 0` in `message_start` and send the real count in `message_delta`. Claude Code uses the final value. |
| **Error events** — Anthropic sends `event: error` with typed error objects. OpenAI sends error JSON or HTTP error status. | We translate HTTP errors to Anthropic error event format. |

---

## 5. Anthropic features LOST in translation (no OpenAI / Copilot equivalent)

These are features that Claude Code may send or expect, but cannot be passed through to Copilot.

### 5.1 Extended thinking

- **What it is:** `thinking` parameter (`{type: "enabled", budget_tokens: N}`) that makes Claude show its reasoning process as `thinking` content blocks in the response.
- **Claude Code usage:** Claude Code uses extended thinking for complex reasoning tasks.
- **What happens:** The `thinking` parameter is **stripped** from the request. Copilot will not return thinking blocks. The model still reasons internally — it just won't expose the trace.
- **Impact:** **Medium.** Claude Code still gets correct answers, but loses visibility into the model's reasoning chain. The model itself may produce slightly different (potentially less thorough) output without the explicit thinking budget.

### 5.2 Prompt caching

- **What it is:** `cache_control: {type: "ephemeral"}` on content blocks, allowing the API to cache and reuse long prefixes.
- **Claude Code usage:** Claude Code uses caching to reduce latency and cost on repeated context (e.g., large file contents sent in every turn).
- **What happens:** `cache_control` fields are **stripped**. Every request to Copilot sends the full context.
- **Impact:** **Low-Medium.** Slightly higher latency on long conversations. No functional impact. Copilot may have its own internal caching.

### 5.3 Citations

- **What it is:** Response content blocks can include `citations` referencing source documents.
- **Claude Code usage:** Not typically used by Claude Code.
- **Impact:** **None** for Claude Code.

### 5.4 PDF input

- **What it is:** Anthropic natively accepts PDF documents as content blocks (`{type: "document", source: {type: "base64", ...}}`).
- **Claude Code usage:** Claude Code may send PDFs if the user references them.
- **What happens:** PDF content blocks have no OpenAI equivalent and are **stripped**.
- **Impact:** **Low.** Claude Code primarily works with text files. If a PDF is referenced, the proxy would need to log a warning.

### 5.5 `anthropic-beta` header

- **What it is:** Feature flags for experimental API features (e.g., `computer-use-2025-11-24`, `files-api-2025-04-14`).
- **Claude Code usage:** Claude Code may send beta headers for features like computer use.
- **What happens:** **Stripped** before forwarding to Copilot. Beta features are not available.
- **Impact:** **Low.** Computer use and file API are specialized features not core to Claude Code's coding workflow.

### 5.6 `metadata` field

- **What it is:** Optional `metadata: {user_id: "..."}` for tracking.
- **What happens:** **Stripped.**
- **Impact:** **None** functionally.

### 5.7 Thinking / signature content blocks in responses

- **What it is:** When extended thinking is enabled, responses include `thinking` and `signature` content blocks.
- **What happens:** Since we strip `thinking` from the request, Copilot won't return these blocks. On the response side, OpenAI format has no equivalent, so even if they appeared, they'd have no translation target.
- **Impact:** **None** — these blocks won't appear since we don't request them.

---

## 6. OpenAI response features NOT present in Anthropic format

These are fields Copilot may return that have no home in the Anthropic response schema.

| OpenAI field | What we do | Impact |
|---|---|---|
| `choices` array (n > 1) | Copilot always returns n=1 for Claude models. We take `choices[0]`. | None |
| `system_fingerprint` | Dropped. | None |
| `created` (Unix timestamp) | Dropped. Anthropic responses don't include creation time. | None |
| `logprobs` | Dropped. Anthropic has no equivalent field. | None |
| `object: "chat.completion"` | Replaced with `type: "message"`. | None |
| `choices[0].message.refusal` | Mapped to `stop_reason: "refusal"` if present. | Minimal |

**Impact:** None of these affect Claude Code's functionality.

---

## 7. Copilot-specific unknowns and risks

### 7.1 Tool calling support for Claude models via Copilot API

- GitHub Copilot Chat in VS Code and on the web **does** support tool calling with Claude models.
- However, it is **not explicitly documented** whether the raw `api.githubcopilot.com/chat/completions` endpoint passes through OpenAI-style `tools` and `tool_calls` for Claude model requests.
- **Risk:** If Copilot strips or ignores `tools` in the request body for Claude models, tool use will silently fail. Claude Code would receive responses that never invoke tools.
- **Mitigation:** Test immediately after implementing. If tool calling doesn't work, the proxy is limited to chat-only interactions.

### 7.2 Streaming format from Copilot

- Copilot's streaming is expected to follow OpenAI SSE format, but minor deviations are possible (e.g., extra fields, different chunking of tool call arguments).
- **Mitigation:** The stream translator should be lenient — ignore unknown fields, handle missing `usage` gracefully.

### 7.3 Rate limits

- Copilot Pro has per-model rate limits that differ from Anthropic's limits.
- Claude Code has its own retry/backoff logic tuned for Anthropic's rate limit headers (`x-ratelimit-*`).
- **What happens:** Copilot returns `429` with different headers. We should translate Copilot's `429` into an Anthropic-format error event.
- **Risk:** Claude Code's retry logic may not align perfectly with Copilot's rate limit windows.

### 7.4 Max context length

- Anthropic Claude models support 200K+ tokens natively.
- Through Copilot, the effective context window may be smaller (Copilot may impose its own limits).
- **Risk:** Long Claude Code sessions with large file contexts could hit Copilot's context limit before hitting the model's native limit.

---

## 8. Summary: what works vs. what doesn't

### Will work with Claude Code

- Text conversation (multi-turn, system prompts)
- Tool definitions, tool calls, tool results (pending Copilot API verification — see 7.1)
- Streaming responses
- Model selection (Opus 4.6, Sonnet 4.6, Sonnet 4.5)
- Default model injection
- `x-api-key` / `Authorization` authentication

### Degraded but functional

- **Extended thinking** — stripped; model still works but may give less thorough responses on complex tasks
- **Prompt caching** — stripped; slightly higher latency on long conversations
- **Image input** — translated but untested through Copilot; may not work for Claude models via Copilot

### Will not work

- **PDF input** — no OpenAI equivalent; stripped with warning
- **Computer use** — requires `anthropic-beta` header and specialized tool types; stripped
- **Citations** — no OpenAI equivalent; not returned
- **Thinking blocks in responses** — not available through OpenAI format

---

## 9. Recommendation

The translation covers the **core functionality** Claude Code needs: text, tools, and streaming. The gaps are primarily in advanced/optional features (extended thinking, caching, PDFs) that don't block Claude Code from functioning as a coding assistant.

The **highest-risk unknown** is whether Copilot's API supports tool calling for Claude models (Section 7.1). This should be validated as the first integration test after implementation. If it doesn't work, we may need to explore alternative approaches (e.g., using GPT models for tool-heavy interactions, or finding if Copilot has an undocumented tool-passing mechanism).
