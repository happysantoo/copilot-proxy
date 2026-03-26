# Token Refresh and Authentication Debugging

This guide explains how authentication works in CopiProxy, what happens on `401` errors, and how to diagnose whether an issue is a normal runtime token expiration or a truly dead/revoked token.

---

## 1. Two-token model (important)

CopiProxy uses two different tokens:

1. **Stored GitHub token (long-lived key)**
   - Saved in CopiProxy (via device flow or admin API).
   - Used to call GitHub's Copilot token endpoint.
   - If this token is revoked/expired/invalid, refresh cannot recover automatically.

2. **Copilot runtime token (short-lived bearer)**
   - Fetched from `https://api.github.com/copilot_internal/v2/token`.
   - Sent to `https://api.githubcopilot.com` for model requests.
   - Designed to expire regularly and be refreshed automatically.

---

## 2. Current behavior on 401

CopiProxy now handles both major 401 paths:

### A) 401 from Copilot chat endpoint (`api.githubcopilot.com`)

Flow:
- Send request with cached runtime token
- Upstream returns `401`
- Proxy runs `refreshToken()`
- Proxy retries once

Outcome:
- If retry succeeds: transient runtime expiry recovered automatically.
- If retry fails: response is returned as Anthropic-formatted `authentication_error`.

### B) 401 while fetching runtime token (`api.github.com/copilot_internal/v2/token`)

Flow:
- Proxy tries to resolve runtime token
- GitHub token API returns `401`
- Proxy triggers refresh/retry path once
- If still failing, proxy returns Anthropic-formatted `authentication_error`

Outcome:
- Usually indicates stored GitHub token is invalid/revoked/expired.

---

## 3. What “dead token” means

A token is effectively **dead** when retries cannot recover and every call to the GitHub token API fails with `401`.

Typical causes:
- User revoked GitHub authorization
- Token was rotated/replaced elsewhere
- Token is no longer valid for Copilot access
- Wrong key was set as default in CopiProxy

When dead, the only fix is **re-authentication** (new device flow / new key registration).

---

## 4. How to distinguish temporary vs dead

Use this quick decision table:

| Signal | Likely meaning |
|---|---|
| One `401`, then success after retry | Normal runtime token expiration |
| Repeated `401` on every request even after refresh | Stored GitHub token is dead/invalid |
| `refresh-meta` endpoint fails with auth error | Stored GitHub token is dead/invalid |

---

## 5. Step-by-step debugging workflow

### Step 1: Enable temporary debug logs

In `application.yml` (or env overrides), set:

```yaml
logging:
  level:
    com.copiproxy.service: DEBUG
    com.copiproxy.web: DEBUG
```

Optional (very verbose payloads):

```yaml
logging:
  level:
    com.copiproxy.web.ProxyController: TRACE
```

### Step 2: Validate local proxy health

```bash
curl http://localhost:3000/health
```

### Step 3: Inspect stored keys

```bash
curl http://localhost:3000/admin/api-keys
```

Check:
- Is there a default key?
- Is it the key you expect?
- Does metadata look stale or missing?

### Step 4: Force metadata refresh for a specific key

```bash
curl -X POST http://localhost:3000/admin/api-keys/<id>/refresh-meta
```

Interpretation:
- Success -> stored GitHub token is still valid.
- Auth failure (`401`/`authentication_error`) -> stored token likely dead.

### Step 5: Trigger a normal model request and observe logs

Send any Claude Code request (or curl to `/v1/messages`) and inspect sequence:

Expected healthy transient sequence:
- First attempt fails with 401
- Log indicates refresh and retry
- Retry succeeds

Dead-token sequence:
- First attempt fails
- Refresh attempted
- Retry fails with same auth error
- Client receives Anthropic-formatted `authentication_error`

---

## 6. Log patterns to look for

Helpful indicators:

- `Got 401 from Copilot, refreshing token and retrying`
  - Runtime token expired and auto-refresh path is running.

- `Evicted expired Copilot token, fetching fresh one`
  - Refresh flow started.

- Errors containing `Copilot token API failed with 401`
  - GitHub token endpoint rejected the stored token.
  - Strong signal that stored GitHub token is dead/invalid.

---

## 7. Recovery actions

### If temporary runtime expiry
No manual action required. Proxy should self-heal.

### If stored GitHub token is dead
1. Re-run device flow and obtain a new key.
2. Register key in CopiProxy.
3. Set new key as default.
4. Re-test with `/v1/messages`.

Example admin flow:

```bash
# list keys
curl http://localhost:3000/admin/api-keys

# set default key
curl -X POST http://localhost:3000/admin/api-keys/default \
  -H "Content-Type: application/json" \
  -d '{"id":"<new-key-id>"}'
```

---

## 8. Common pitfalls

- **Using placeholder `_` without a valid default key**
  - Claude Code often uses `x-api-key: _`; proxy then depends entirely on default stored key.

- **Corporate proxy + missing `no_proxy` for localhost**
  - Claude Code cannot reach local proxy reliably.
  - Set: `no_proxy=localhost,127.0.0.1`

- **Confusing token layers**
  - Runtime token expiry is normal.
  - Stored GitHub token invalidation requires re-authentication.

---

## 9. Recommended operator checklist

When users report auth failures:

1. Confirm `/health` works.
2. Confirm default key exists.
3. Run `/admin/api-keys/<id>/refresh-meta`.
4. Check logs for whether retry succeeds or keeps failing.
5. If repeated 401 from token endpoint: rotate/re-auth stored key.

This reduces guesswork and quickly separates transient expiration from true credential invalidation.
