# Corporate proxy: GitHub and Copilot API calls

This document explains how CopiProxy handles corporate HTTP/HTTPS proxies, what is supported out of the box, and what needs manual configuration for advanced setups.

## Built-in support (recommended)

CopiProxy **automatically** reads the standard proxy environment variables at startup and maps them to JVM system properties before any outbound call is made:

| Environment variable | JVM system property |
|----------------------|---------------------|
| `https_proxy` / `HTTPS_PROXY` | `https.proxyHost`, `https.proxyPort` |
| `http_proxy` / `HTTP_PROXY` | `http.proxyHost`, `http.proxyPort` |
| `no_proxy` / `NO_PROXY` | `http.nonProxyHosts` |

**Just set the environment variables and start the proxy:**

```bash
export https_proxy=http://proxy.corp.example.com:8080
export http_proxy=http://proxy.corp.example.com:8080
export no_proxy=localhost,127.0.0.1,.corp.example.com
mvn spring-boot:run
```

On startup you will see:

```
Configured HTTPS proxy from environment: proxy.corp.example.com:8080
Configured HTTP proxy from environment: proxy.corp.example.com:8080
Configured non-proxy hosts from environment: localhost|127.0.0.1|*.corp.example.com
```

### Precedence

- Lowercase env var (`https_proxy`) takes precedence over uppercase (`HTTPS_PROXY`).
- Explicit JVM flags (`-Dhttps.proxyHost=...`) are **never overwritten** — they take precedence over environment variables.
- `no_proxy` entries with a leading dot (`.example.com`) are automatically converted to JVM wildcard format (`*.example.com`).

### Limitations of built-in support

- **Proxy authentication** (username/password in the URL) is detected and a warning is logged, but JVM system properties do not support proxy auth natively. Use a local proxy helper like **CNTLM** or **px** for NTLM/Kerberos/Basic auth (see section below).
- **PAC files** are not supported. Use a local proxy that implements PAC and expose a single `host:port`.
- **SOCKS proxies** (`all_proxy` / `ALL_PROXY`) are not mapped.

## How it works

CopiProxy uses Java's built-in **`java.net.http.HttpClient`** (Java 21) in two places:

| Component | Role | Typical hosts |
|-----------|------|----------------|
| `GithubCopilotService` | OAuth device flow, GitHub token exchange, **Copilot runtime token** (`/copilot_internal/v2/token`) | `github.com`, `api.github.com` |
| `ProxyService` | Forwards chat and models traffic to **Copilot API** | `api.githubcopilot.com` |

Both clients use the JVM's **default `ProxySelector`**, which reads `http.proxyHost`, `https.proxyHost`, and `http.nonProxyHosts` system properties. The `ProxyEnvironmentConfig` class (called from `main()` before Spring starts) maps the environment variables into those properties, so the default routing works transparently.

## Why nothing works without proxy config

Many corporate networks:

- Allow outbound HTTPS only via an **explicit HTTP(S) forward proxy** (often with authentication).
- Block or restrict **direct** TCP connections to `github.com`, `api.github.com`, and `api.githubcopilot.com`.

Without the proxy environment variables (or equivalent JVM flags), the `HttpClient` tries a direct connection, which times out or is reset.

## Alternative approaches

### 1. JVM `-D` flags (override env vars)

If you need to override what the environment variables would set, pass properties directly:

```bash
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dhttps.proxyHost=proxy.corp.example.com -Dhttps.proxyPort=8080 -Dhttp.proxyHost=proxy.corp.example.com -Dhttp.proxyPort=8080 -Dhttp.nonProxyHosts=localhost|127.0.0.1"
```

Or run the JAR:

```bash
java -Dhttps.proxyHost=... -Dhttps.proxyPort=8080 -jar target/copiproxy-0.1.0.jar
```

Explicit `-D` flags always take precedence over env vars.

### 2. `JAVA_TOOL_OPTIONS`

IT may inject proxy config globally:

```bash
export JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=... -Dhttps.proxyPort=8080 ..."
```

This applies to every Java process on the machine. The same precedence applies — these are set before `main()` runs, so they take precedence over env var mapping.

### 3. Local proxy helper (for auth, PAC, or NTLM)

When the corporate proxy requires **NTLM, Kerberos, or Basic authentication**, use a local helper:

- **CNTLM** — lightweight NTLM proxy authenticator
- **px** — Python-based proxy helper for corporate SSO

Run the helper on `127.0.0.1:3128`, then point CopiProxy at it:

```bash
export https_proxy=http://127.0.0.1:3128
export http_proxy=http://127.0.0.1:3128
export no_proxy=localhost,127.0.0.1
mvn spring-boot:run
```

This avoids putting passwords in environment variables or on the command line.

### 4. TLS inspection (HTTPS MITM)

If the corporate proxy does **SSL inspection**, the JVM must **trust the corporate root CA**. Without that, you get certificate errors (different from connection timeouts).

Import the enterprise root into a **truststore** and run with:

```bash
java -Djavax.net.ssl.trustStore=/path/to/corp-truststore.jks \
     -Djavax.net.ssl.trustStorePassword=changeit \
     -jar target/copiproxy-0.1.0.jar
```

Coordinate with IT for the correct CA bundle.

## Proxy authentication

JVM system properties alone do **not** handle proxy authentication (HTTP 407 responses). If you see `407 Proxy Authentication Required`:

1. **Recommended:** Use a local proxy helper (CNTLM, px) that handles auth and exposes an unauthenticated `host:port` to Java.
2. **Workaround:** Some corporate setups allow SSO/NTLM passthrough without explicit credentials.
3. **Future:** Application-level `Authenticator.setDefault(...)` support could be added if demand warrants it.

Credentials in the proxy URL (`http://user:pass@host:port`) are **detected and warned about** at startup but are not applied — use a local proxy helper instead.

## Quick checklist

| Step | Action |
|------|--------|
| 1 | Confirm with IT: proxy host, port, auth type (none / Basic / NTLM / SSO), PAC URL |
| 2 | From the same machine, test `curl -v https://api.github.com` with `https_proxy` set |
| 3 | Set `https_proxy`, `http_proxy`, and `no_proxy` in your shell environment |
| 4 | Start CopiProxy — check logs for "Configured HTTPS proxy from environment" |
| 5 | If TLS errors, add corporate CA to truststore |
| 6 | If 407 Proxy Authentication Required, use a local proxy helper (CNTLM/px) |

## Summary

- CopiProxy **reads `http_proxy`, `https_proxy`, and `no_proxy`** from the environment at startup and maps them to JVM system properties automatically.
- Explicit `-D` JVM flags always take precedence over environment variables.
- For **proxy authentication** (NTLM, Kerberos, Basic), use a local proxy helper like CNTLM.
- For **TLS inspection**, import the corporate CA into a Java truststore.
