import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch

from usage_bridge import BridgeError, Source, handler, normalize, read_usage


class BridgeTest(unittest.TestCase):
    def test_windows(self):
        raw = {"rateLimits": {"primary": {"usedPercent": 83, "windowDurationMins": 300}},
               "rateLimitsByLimitId": {"reviews": {"secondary": {"usedPercent": None}}}}
        data = normalize({"email": "private", "planType": "plus"}, raw, 123)
        self.assertNotIn("private", json.dumps(data))
        self.assertEqual(data["limits"][1]["usedPercent"], 83)
        self.assertIsNone(data["limits"][0]["usedPercent"])
        self.assertEqual(normalize({}, {}, 1)["limits"], [])

    def test_rate_backoff(self):
        calls = []
        def throttled():
            calls.append(1)
            raise BridgeError(429)
        source = Source(throttled)
        for _ in range(2):
            with self.assertRaises(BridgeError):
                source.get()
        self.assertEqual(len(calls), 1)

    def test_rpc_handshake(self):
        # Isolated executable fixture exercises the actual stdio client without any account.
        with tempfile.TemporaryDirectory() as tmp:
            cli = Path(tmp, "codex")
            cli.write_text("""#!/usr/bin/env python3
import json, sys
for method in ['initialize','initialized','account/read','account/rateLimits/read']:
    req = json.loads(input())
    assert method == req['method']
    if 'id' in req:
        result = {'account': {'type': 'chatgpt'}} if method == 'account/read' else {}
        print(json.dumps({'id': req['id'], 'result': result}),flush=True)
""")
            cli.chmod(0o700)
            with patch.dict(os.environ, PATH=tmp + os.pathsep + os.environ["PATH"]):
                self.assertEqual(read_usage()["source"], "codex-app-server")

    def test_http_auth_and_failures(self):
        def offline():
            raise BridgeError(503)
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler("test-only", Source(offline)))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            url = f"http://127.0.0.1:{server.server_port}/usage"
            for headers, status in [({}, 401), ({"Authorization": "Bearer test-only"}, 503)]:
                with self.assertRaises(HTTPError) as error:
                    urlopen(Request(url, headers=headers))
                self.assertEqual(error.exception.code, status)
        finally:
            server.shutdown()
            server.server_close()

    def test_init_preserves_pairing(self):
        with tempfile.TemporaryDirectory() as tmp:
            cmd = [sys.executable, str(Path(__file__).with_name("usage_bridge.py")), "init"]
            env = dict(os.environ, HOME=tmp)
            first = subprocess.run(cmd, env=env, capture_output=True)
            token = Path(tmp, ".config/codex-usage-widget/bridge.token")
            original = token.read_bytes()
            self.assertEqual(first.returncode, 0)
            self.assertEqual(token.stat().st_mode & 0o777, 0o600)
            self.assertNotIn(original.strip(), first.stdout)
            self.assertNotEqual(subprocess.run(cmd, env=env, capture_output=True).returncode, 0)
            self.assertEqual(original, token.read_bytes())
