"""Export a safe subset of local Codex quota data or serve it to a paired phone."""
import argparse
import hmac
import json
import math
import os
from pathlib import Path
import queue
import secrets
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN_PATH = Path.home() / ".config" / "codex-usage-widget" / "bridge.token"


class BridgeError(Exception):
    def __init__(self, status):
        self.status = status


def read_usage():
    try:
        process = subprocess.Popen(["codex", "app-server"], stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                   text=True, encoding="utf-8")
    except OSError as exc:
        raise BridgeError(503) from exc
    replies = queue.Queue()
    deadline = time.monotonic() + 24

    def reader():
        try:
            for line in process.stdout:
                msg = json.loads(line)
                if "id" in msg:
                    replies.put(msg)
        finally:
            replies.put(None)

    def send(method, params=None, rpc_id=None):
        msg = {"method": method}
        if params is not None:
            msg["params"] = params
        if rpc_id is not None:
            msg["id"] = rpc_id
        process.stdin.write(json.dumps(msg) + "\n")
        process.stdin.flush()
        if rpc_id is None:
            return
        while True:
            reply = replies.get(timeout=max(0, deadline - time.monotonic()))
            if reply is None:
                raise BridgeError(503)
            if reply.get("id") != rpc_id:
                continue
            if "error" in reply:
                error = reply["error"]
                message = str(error).lower()
                if any(s in message for s in ("429", "too many requests", "rate limited")):
                    raise BridgeError(429)
                if any(s in message for s in ("401", "unauthorized", "sign in", "authentication")):
                    raise BridgeError(409)
                raise BridgeError(503)
            return reply["result"]

    threading.Thread(target=reader, daemon=True).start()
    try:
        send("initialize", {"clientInfo": {"name": "codex_usage_widget", "version": "0.1.0"}}, 1)
        send("initialized")
        account = send("account/read", {"refreshToken": False}, 2).get("account")
        if not account or account.get("type") != "chatgpt":
            raise BridgeError(409)
        limits = send("account/rateLimits/read", {}, 3)
        return normalize(account, limits, int(time.time()))
    except (queue.Empty, OSError, KeyError, ValueError) as exc:
        raise BridgeError(503) from exc
    finally:
        process.kill()
        process.wait()
        process.stdin.close()
        process.stdout.close()


def normalize(account, response, now):
    limits = []
    buckets = dict(response.get("rateLimitsByLimitId") or {})
    fallback = response.get("rateLimits") or {}
    if fallback:
        buckets.setdefault(fallback.get("limitId") or "codex", fallback)
    plan = account.get("planType")
    for key, bucket in buckets.items():
        plan = plan or bucket.get("planType")
        label = bucket.get("limitName") or key
        for slot in ("primary", "secondary", "individualLimit"):
            window = bucket.get(slot)
            if not isinstance(window, dict):
                continue
            minutes = window.get("windowDurationMins")
            used = window.get("usedPercent")
            if slot == "individualLimit":
                rem = window.get("remainingPercent")
                used = 100 - rem if isinstance(rem, (float, int)) else None
            if type(used) not in (int, float) or not math.isfinite(used) or not 0 <= used <= 100:
                used = None
            duration = {300: "5-hour", 10080: "Weekly"}.get(minutes)
            title = duration or (f"{minutes} min" if minutes else slot)
            limits.append({"id": f"{key}/{slot}", "name": f"{label} · {title}",
                           "usedPercent": used, "windowMinutes": minutes,
                           "resetsAt": window.get("resetsAt")})
    return {"schemaVersion": 1, "fetchedAt": now, "source": "codex-app-server",
            "account": {"label": "ChatGPT", "plan": plan}, "limits": limits}


class Source:
    def __init__(self, fetch=read_usage):
        self.fetch = fetch
        self.lock = threading.Lock()
        self.until = 0
        self.cached = None
        self.error = None

    def get(self):
        with self.lock:
            if time.monotonic() >= self.until:
                try:
                    self.cached = self.fetch()
                    self.error = None
                except BridgeError as exc:
                    self.error = exc
                self.until = time.monotonic() + (300 if self.error and self.error.status == 429 else 60)
            if self.error:
                raise self.error
            return self.cached


def handler(token, source):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != "/usage":
                self.send_error(404)
                return
            if not hmac.compare_digest(self.headers.get("Authorization", "").encode(),
                                       ("Bearer " + token).encode()):
                self.send_error(401)
                return
            try:
                data = json.dumps(source.get(), allow_nan=False).encode()
            except BridgeError as exc:
                self.send_response(exc.status)
                self.send_header("Retry-After", "300" if exc.status == 429 else "60")
                self.end_headers()
                return
            self.send_response(200)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *_):
            pass
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["init", "serve", "export"])
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    if args.command == "init":
        TOKEN_PATH.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with os.fdopen(os.open(TOKEN_PATH, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), "w") as out:
            out.write(secrets.token_urlsafe(32) + "\n")
        print(f"Pairing token saved to {TOKEN_PATH}.")
    elif args.command == "export":
        print(json.dumps(read_usage(), indent=2))
    else:
        token = TOKEN_PATH.read_text().strip()
        if len(token) < 32:
            sys.exit("Invalid bridge token. Initialize a new token file.")
        with ThreadingHTTPServer(("127.0.0.1", args.port), handler(token, Source())) as server:
            print(f"Listening on loopback port {args.port}. Use a private HTTPS reverse proxy.", flush=True)
            server.serve_forever()


if __name__ == "__main__":
    try:
        main()
    except BridgeError as error:
        sys.exit(f"Codex usage unavailable ({error.status}). Check codex login status and connectivity.")
    except FileExistsError:
        sys.exit("A pairing token already exists. Existing pairings were preserved.")
    except FileNotFoundError:
        sys.exit("Initialize the pairing token first.")
