#!/usr/bin/env python3
"""
Copiproxy dev starter: optionally run ``mvn spring-boot:run``, wait for ``GET /health``,
probe the default API key via ``POST /admin/api-keys/{id}/refresh-meta``, and if needed
run GitHub device OAuth (same JSON/headers as GithubCopilotService), then register the
token and ``POST /admin/api-keys/default``.

Environment: ``http_proxy`` / ``https_proxy`` / ``no_proxy``; ``SSL_CERT_FILE`` for custom CAs.
Admin API is unauthenticated — bind to 127.0.0.1 only.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
import webbrowser
from pathlib import Path
from typing import Any, Callable, Optional

DEFAULT_DEVICE_CODE_URL = "https://github.com/login/device/code"
DEFAULT_OAUTH_URL = "https://github.com/login/oauth/access_token"
DEFAULT_CLIENT_ID = "Iv1.b507a08c87ecfe98"
DEFAULT_SCOPE = "read:user"

GITHUB_OAUTH_HEADERS = {
    "accept": "application/json",
    "content-type": "application/json",
    "editor-version": "Neovim/0.6.1",
    "editor-plugin-version": "copilot.vim/1.16.0",
    "user-agent": "GithubCopilot/1.155.0",
}

_urlopen: Callable[..., Any] = urllib.request.urlopen


def _repo_root_from_script() -> Path:
    return Path(__file__).resolve().parent.parent.parent


def _find_maven() -> str:
    for name in ("mvn", "mvn.cmd"):
        p = shutil.which(name)
        if p:
            return p
    raise FileNotFoundError("mvn not found on PATH")


def _terminate_child(child: Optional[subprocess.Popen]) -> None:
    if child is None or child.poll() is not None:
        return
    try:
        if sys.platform == "win32":
            child.terminate()
            try:
                child.wait(timeout=15)
            except subprocess.TimeoutExpired:
                child.kill()
        else:
            try:
                os.killpg(child.pid, signal.SIGTERM)
            except (ProcessLookupError, PermissionError):
                child.terminate()
            try:
                child.wait(timeout=15)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(child.pid, signal.SIGKILL)
                except (ProcessLookupError, PermissionError):
                    child.kill()
    except Exception:
        pass


def http_json(
    url: str,
    *,
    method: str = "GET",
    data: Optional[dict[str, Any]] = None,
    headers: Optional[dict[str, str]] = None,
    timeout: float = 30,
) -> tuple[int, Any]:
    body = None
    h = dict(headers or {})
    if data is not None:
        body = json.dumps(data).encode("utf-8")
        h.setdefault("content-type", "application/json")
    req = urllib.request.Request(url, data=body, method=method, headers=h)
    try:
        with _urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            if not raw.strip():
                return resp.status, None
            return resp.status, json.loads(raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(raw) if raw.strip() else None
        except json.JSONDecodeError:
            parsed = raw
        raise RuntimeError(f"HTTP {e.code} for {url}: {parsed or raw}") from e


def wait_health(base_url: str, timeout_sec: float, interval_sec: float = 0.5) -> None:
    base = base_url.rstrip("/")
    url = f"{base}/health"
    deadline = time.monotonic() + timeout_sec
    last_err: Optional[str] = None
    while time.monotonic() < deadline:
        try:
            status, body = http_json(url, method="GET", timeout=min(10.0, interval_sec * 4))
            if status == 200 and isinstance(body, dict) and body.get("status") == "ok":
                return
            last_err = f"unexpected response {status}: {body!r}"
        except Exception as ex:
            last_err = str(ex)
        time.sleep(interval_sec)
    raise TimeoutError(f"Timed out after {timeout_sec}s waiting for {url}. Last error: {last_err}")


def request_device_code(
    client_id: str,
    scope: str,
    device_code_url: str = DEFAULT_DEVICE_CODE_URL,
) -> dict[str, Any]:
    _, body = http_json(
        device_code_url,
        method="POST",
        data={"client_id": client_id, "scope": scope},
        headers=GITHUB_OAUTH_HEADERS,
        timeout=30,
    )
    if not isinstance(body, dict):
        raise RuntimeError(f"Unexpected device code response: {body!r}")
    return body


def poll_access_token(
    client_id: str,
    device_code: str,
    oauth_url: str = DEFAULT_OAUTH_URL,
    interval_sec: float = 5.0,
    expires_in_sec: float = 900.0,
) -> str:
    deadline = time.monotonic() + expires_in_sec
    interval = max(float(interval_sec), 1.0)
    payload = {
        "client_id": client_id,
        "device_code": device_code,
        "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
    }
    while time.monotonic() < deadline:
        req = urllib.request.Request(
            oauth_url,
            data=json.dumps(payload).encode("utf-8"),
            method="POST",
            headers=GITHUB_OAUTH_HEADERS,
        )
        try:
            with _urlopen(req, timeout=30) as resp:
                body = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            body = json.loads(e.read().decode("utf-8", errors="replace"))

        if not isinstance(body, dict):
            raise RuntimeError(f"Unexpected token response: {body!r}")

        err = body.get("error")
        if err == "authorization_pending":
            time.sleep(interval)
            continue
        if err == "slow_down":
            interval += 5
            time.sleep(interval)
            continue
        if err == "expired_token":
            raise RuntimeError("Device flow expired before authorization completed.")
        if err == "access_denied":
            raise RuntimeError("Device flow denied by user.")
        if err:
            raise RuntimeError(
                f"OAuth error: {err} — {body.get('error_description', '')}".strip()
            )

        token = body.get("access_token")
        if token:
            return str(token)

        time.sleep(interval)

    raise TimeoutError("Device flow timed out waiting for access_token.")


def set_clipboard(text: str) -> bool:
    if sys.platform == "win32":
        return _clipboard_windows_powershell(text)
    if sys.platform == "darwin":
        try:
            subprocess.run(["pbcopy"], input=text.encode("utf-8"), check=True, timeout=10)
            return True
        except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            return False
    for cmd in (
        ["wl-copy"],
        ["xclip", "-selection", "clipboard"],
        ["xsel", "--clipboard", "--input"],
    ):
        try:
            subprocess.run(cmd, input=text.encode("utf-8"), check=True, timeout=10)
            return True
        except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            continue
    return False


def _clipboard_windows_powershell(text: str) -> bool:
    b64 = base64.b64encode(text.encode("utf-16-le")).decode("ascii")
    ps = (
        "$s = [Console]::InputEncoding.GetString("
        f"[System.Convert]::FromBase64String('{b64}')); "
        "Set-Clipboard -Value $s"
    )
    try:
        cflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        subprocess.run(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps],
            check=True,
            timeout=30,
            creationflags=cflags,
        )
        return True
    except (FileNotFoundError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
        return False


def admin_list_keys(base_url: str) -> list[dict[str, Any]]:
    base = base_url.rstrip("/")
    status, body = http_json(f"{base}/admin/api-keys", method="GET", timeout=30)
    if status != 200 or not isinstance(body, list):
        raise RuntimeError(f"Unexpected list keys response: {status} {body!r}")
    return body


def row_is_default(row: dict[str, Any]) -> bool:
    return row.get("isDefault") is True or row.get("default") is True


def find_default_key_id(keys: list[dict[str, Any]]) -> Optional[str]:
    for row in keys:
        if isinstance(row, dict) and row_is_default(row):
            kid = row.get("id")
            return str(kid) if kid else None
    return None


def admin_refresh_meta(base_url: str, key_id: str) -> bool:
    base = base_url.rstrip("/")
    url = f"{base}/admin/api-keys/{key_id}/refresh-meta"
    req = urllib.request.Request(
        url, data=b"", method="POST", headers={"content-type": "application/json"}
    )
    try:
        with _urlopen(req, timeout=60) as resp:
            return 200 <= resp.status < 300
    except urllib.error.HTTPError as e:
        return 200 <= e.code < 300
    except Exception:
        return False


def admin_create_key(base_url: str, name: str, key: str) -> str:
    base = base_url.rstrip("/")
    status, body = http_json(
        f"{base}/admin/api-keys",
        method="POST",
        data={"name": name, "key": key},
        headers={"content-type": "application/json"},
        timeout=60,
    )
    if status not in (200, 201) or not isinstance(body, dict):
        raise RuntimeError(f"Unexpected create key response: {status} {body!r}")
    kid = body.get("id")
    if not kid:
        raise RuntimeError(f"Create key response missing id: {body!r}")
    return str(kid)


def admin_set_default(base_url: str, key_id: str) -> None:
    base = base_url.rstrip("/")
    status, body = http_json(
        f"{base}/admin/api-keys/default",
        method="POST",
        data={"id": key_id},
        headers={"content-type": "application/json"},
        timeout=30,
    )
    if status != 200 or not isinstance(body, dict):
        raise RuntimeError(f"Unexpected set default response: {status} {body!r}")


def run_device_flow_interactive(
    client_id: str,
    scope: str,
    *,
    open_browser_flag: bool = True,
    clipboard_flag: bool = True,
    device_code_url: str = DEFAULT_DEVICE_CODE_URL,
    oauth_url: str = DEFAULT_OAUTH_URL,
) -> str:
    init = request_device_code(client_id, scope, device_code_url)
    user_code = init.get("user_code")
    device_code = init.get("device_code")
    verification_uri = init.get("verification_uri") or "https://github.com/login/device"
    interval = float(init.get("interval") or 5)
    expires_in = float(init.get("expires_in") or 900)

    if not user_code or not device_code:
        raise RuntimeError(f"Missing user_code or device_code in response: {init!r}")

    print(f"User code: {user_code}\nOpen: {verification_uri}")

    if clipboard_flag:
        if set_clipboard(str(user_code)):
            print("User code copied to clipboard.")
        else:
            print("Could not copy to clipboard; paste manually.", file=sys.stderr)
    if open_browser_flag:
        webbrowser.open(str(verification_uri))

    return poll_access_token(
        client_id,
        str(device_code),
        oauth_url=oauth_url,
        interval_sec=interval,
        expires_in_sec=expires_in,
    )


def start_maven(project_dir: Path) -> subprocess.Popen:
    mvn = _find_maven()
    kwargs: dict[str, Any] = {"cwd": str(project_dir), "stdin": subprocess.DEVNULL}
    if sys.platform == "win32":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    return subprocess.Popen([mvn, "spring-boot:run"], **kwargs)


def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Start Copiproxy, run GitHub device flow if needed, set default API key."
    )
    p.add_argument("--project-dir", type=Path, default=None, help="Directory with pom.xml")
    p.add_argument("--skip-start", action="store_true", help="App already running")
    p.add_argument(
        "--base-url",
        default=os.environ.get("COPROXY_BASE_URL", "http://127.0.0.1:3000"),
        help="Copiproxy base URL",
    )
    p.add_argument("--health-timeout", type=float, default=300.0, help="Wait for /health (s)")
    p.add_argument("--force-auth", action="store_true", help="Skip refresh-meta shortcut")
    p.add_argument(
        "--github-client-id",
        default=os.environ.get("COPROXY_GITHUB_CLIENT_ID", DEFAULT_CLIENT_ID),
    )
    p.add_argument("--scope", default=DEFAULT_SCOPE)
    p.add_argument("--device-code-url", default=DEFAULT_DEVICE_CODE_URL, help=argparse.SUPPRESS)
    p.add_argument("--oauth-url", default=DEFAULT_OAUTH_URL, help=argparse.SUPPRESS)
    p.add_argument("--key-name", default=None)
    p.add_argument("--no-browser", action="store_true")
    p.add_argument("--no-clipboard", action="store_true")
    args = p.parse_args(argv)
    if args.project_dir is None:
        args.project_dir = _repo_root_from_script()
    return args


def main(argv: Optional[list[str]] = None) -> int:
    args = parse_args(argv)
    project_dir = args.project_dir.resolve()
    if not args.skip_start and not (project_dir / "pom.xml").is_file():
        print(f"No pom.xml at {project_dir}", file=sys.stderr)
        return 2

    child: Optional[subprocess.Popen] = None
    old_int = signal.SIGINT
    old_term = signal.SIGTERM

    def handle_signal(signum: int, frame: Any) -> None:
        _terminate_child(child)
        sys.exit(130 if signum == signal.SIGINT else 128 + signum)

    if not args.skip_start:
        old_int = signal.signal(signal.SIGINT, handle_signal)
        old_term = signal.signal(signal.SIGTERM, handle_signal)
        try:
            print(f"Starting Maven in {project_dir} ...")
            child = start_maven(project_dir)
        except Exception:
            signal.signal(signal.SIGINT, old_int)
            signal.signal(signal.SIGTERM, old_term)
            raise

    def restore_signals() -> None:
        if not args.skip_start:
            try:
                signal.signal(signal.SIGINT, old_int)
                signal.signal(signal.SIGTERM, old_term)
            except Exception:
                pass

    try:
        wait_health(args.base_url, args.health_timeout)

        need_auth = True
        if not args.force_auth:
            try:
                keys = admin_list_keys(args.base_url)
                default_id = find_default_key_id(keys)
                if default_id and admin_refresh_meta(args.base_url, default_id):
                    print("Default API key is still valid; skipping device flow.")
                    need_auth = False
            except Exception as ex:
                print(f"Could not probe existing keys ({ex}); will run device flow.", file=sys.stderr)

        if need_auth:
            name = args.key_name or f"DeviceFlow-{int(time.time() * 1000)}"
            token = run_device_flow_interactive(
                args.github_client_id,
                args.scope,
                open_browser_flag=not args.no_browser,
                clipboard_flag=not args.no_clipboard,
                device_code_url=args.device_code_url,
                oauth_url=args.oauth_url,
            )
            kid = admin_create_key(args.base_url, name, token)
            admin_set_default(args.base_url, kid)
            print(f"Registered API key id={kid!r} as default.")

        if child is not None:
            print("Copiproxy is running; press Ctrl+C to stop.")
            code = child.wait()
            restore_signals()
            return int(code) if code is not None else 1
        restore_signals()
        return 0
    except KeyboardInterrupt:
        _terminate_child(child)
        restore_signals()
        return 130
    except Exception as ex:
        print(f"Error: {ex}", file=sys.stderr)
        _terminate_child(child)
        restore_signals()
        return 1


if __name__ == "__main__":
    sys.exit(main())

