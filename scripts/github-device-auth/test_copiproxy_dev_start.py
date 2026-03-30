"""Unit tests for copiproxy_dev_start (stdlib + unittest.mock only)."""
from __future__ import annotations

import io
import json
import unittest
from unittest.mock import patch

import copiproxy_dev_start as cds


class FakeResponse:
    def __init__(self, status: int, body: object):
        self.status = status
        self._body = json.dumps(body).encode("utf-8") if not isinstance(body, bytes) else body

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class HttpJsonTests(unittest.TestCase):
    def test_http_json_get_ok(self):
        def opener(req, timeout=30):
            self.assertEqual(req.full_url, "http://x/health")
            self.assertEqual(req.method, "GET")
            return FakeResponse(200, {"status": "ok"})

        with patch.object(cds, "_urlopen", side_effect=opener):
            status, body = cds.http_json("http://x/health", method="GET")
        self.assertEqual(status, 200)
        self.assertEqual(body, {"status": "ok"})

    def test_http_json_post_raises_runtime(self):
        import urllib.error

        def opener(req, timeout=30):
            raise urllib.error.HTTPError(
                "http://x", 500, "err", {}, io.BytesIO(b'{"msg":"bad"}')
            )

        with patch.object(cds, "_urlopen", side_effect=opener):
            with self.assertRaises(RuntimeError) as ctx:
                cds.http_json("http://x", method="POST", data={})
            self.assertIn("500", str(ctx.exception))


class WaitHealthTests(unittest.TestCase):
    def test_wait_health_success_after_retry(self):
        calls = {"n": 0}

        def opener(req, timeout=30):
            calls["n"] += 1
            if calls["n"] < 2:
                raise OSError("connection refused")
            return FakeResponse(200, {"status": "ok"})

        with patch.object(cds, "_urlopen", side_effect=opener):
            with patch("time.sleep"):
                cds.wait_health("http://127.0.0.1:9", timeout_sec=5.0, interval_sec=0.01)
        self.assertGreaterEqual(calls["n"], 2)

    def test_wait_health_timeout(self):
        def opener(req, timeout=30):
            raise OSError("nope")

        with patch.object(cds, "_urlopen", side_effect=opener):
            with patch("time.sleep"):
                with self.assertRaises(TimeoutError):
                    cds.wait_health("http://x", timeout_sec=0.2, interval_sec=0.05)


class DefaultKeyTests(unittest.TestCase):
    def test_find_default_is_default(self):
        keys = [{"id": "a", "isDefault": False}, {"id": "b", "isDefault": True}]
        self.assertEqual(cds.find_default_key_id(keys), "b")

    def test_find_default_alt_property(self):
        keys = [{"id": "z", "default": True}]
        self.assertEqual(cds.find_default_key_id(keys), "z")


class PollAccessTokenTests(unittest.TestCase):
    def test_poll_pending_then_success(self):
        device = {"device_code": "dc", "client_id": "cid"}
        seq = [
            FakeResponse(200, {"error": "authorization_pending"}),
            FakeResponse(200, {"access_token": "tok"}),
        ]

        def opener(req, timeout=30):
            return seq.pop(0)

        sleeps: list[float] = []

        with patch.object(cds, "_urlopen", side_effect=opener):
            with patch("time.sleep", side_effect=lambda s: sleeps.append(float(s))):
                tok = cds.poll_access_token(
                    "cid",
                    "dc",
                    oauth_url="https://oauth",
                    interval_sec=2.0,
                    expires_in_sec=60.0,
                )
        self.assertEqual(tok, "tok")
        self.assertEqual(sleeps, [2.0])

    def test_slow_down_increases_interval(self):
        seq = [
            FakeResponse(200, {"error": "slow_down"}),
            FakeResponse(200, {"access_token": "t2"}),
        ]

        def opener(req, timeout=30):
            return seq.pop(0)

        sleeps: list[float] = []

        with patch.object(cds, "_urlopen", side_effect=opener):
            with patch("time.sleep", side_effect=lambda s: sleeps.append(float(s))):
                cds.poll_access_token(
                    "c",
                    "d",
                    oauth_url="https://oauth",
                    interval_sec=3.0,
                    expires_in_sec=120.0,
                )
        self.assertEqual(sleeps[0], 8.0)


class RequestDeviceCodeTests(unittest.TestCase):
    def test_payload_and_headers(self):
        captured: dict = {}

        def opener(req, timeout=30):
            captured["url"] = req.full_url
            captured["method"] = req.method
            captured["data"] = json.loads(req.data.decode("utf-8"))
            captured["headers"] = dict(req.header_items())
            return FakeResponse(
                200,
                {
                    "device_code": "dev",
                    "user_code": "USER-1",
                    "verification_uri": "https://github.com/login/device",
                    "interval": 5,
                    "expires_in": 900,
                },
            )

        with patch.object(cds, "_urlopen", side_effect=opener):
            out = cds.request_device_code(
                "Iv1.test",
                "read:user",
                device_code_url="https://github.com/login/device/code",
            )
        self.assertEqual(out["user_code"], "USER-1")
        self.assertEqual(
            captured["data"], {"client_id": "Iv1.test", "scope": "read:user"}
        )
        self.assertEqual(captured["headers"].get("Content-type"), "application/json")
        self.assertEqual(
            captured["headers"].get("Editor-version"), cds.GITHUB_OAUTH_HEADERS["editor-version"]
        )


class AdminRefreshMetaTests(unittest.TestCase):
    def test_true_on_200(self):
        with patch.object(cds, "_urlopen", return_value=FakeResponse(200, {})):
            self.assertTrue(cds.admin_refresh_meta("http://h", "kid"))

    def test_false_on_500_http_error(self):
        import urllib.error

        def opener(req, timeout=30):
            raise urllib.error.HTTPError(
                req.full_url, 500, "x", {}, io.BytesIO(b"{}")
            )

        with patch.object(cds, "_urlopen", side_effect=opener):
            self.assertFalse(cds.admin_refresh_meta("http://h", "kid"))


class MainIntegrationTests(unittest.TestCase):
    @patch("builtins.print")
    @patch.object(cds, "run_device_flow_interactive", return_value="oauth-token")
    @patch.object(cds, "admin_set_default")
    @patch.object(cds, "admin_create_key", return_value="new-id")
    @patch.object(cds, "admin_refresh_meta", return_value=False)
    @patch.object(cds, "admin_list_keys", return_value=[])
    @patch.object(cds, "wait_health")
    def test_skip_start_runs_auth_when_no_keys(
        self, wh, listk, refresh, create, setdef, rdfi, _print
    ):
        rc = cds.main(
            [
                "--skip-start",
                "--base-url",
                "http://127.0.0.1:3000",
                "--health-timeout",
                "1",
                "--key-name",
                "TestKey",
            ]
        )
        self.assertEqual(rc, 0)
        create.assert_called_once()
        setdef.assert_called_once_with("http://127.0.0.1:3000", "new-id")
        rdfi.assert_called_once()

    @patch("builtins.print")
    @patch.object(cds, "run_device_flow_interactive")
    @patch.object(cds, "admin_refresh_meta", return_value=True)
    @patch.object(
        cds,
        "admin_list_keys",
        return_value=[{"id": "x", "isDefault": True}],
    )
    @patch.object(cds, "wait_health")
    def test_skip_auth_when_refresh_ok(self, wh, listk, refresh, rdfi, _print):
        rc = cds.main(["--skip-start", "--base-url", "http://127.0.0.1:3000"])
        self.assertEqual(rc, 0)
        rdfi.assert_not_called()


if __name__ == "__main__":
    unittest.main()

