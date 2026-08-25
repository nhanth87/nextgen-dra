#!/usr/bin/env python3
import http.server
import http.client
import ssl
import os
import sys
import time
import threading

UPSTREAM_HOST = "api.ai-box.vn"
UPSTREAM_PORT = 443
LISTEN_PORT = int(os.environ.get("SHIM_PORT", "8787"))
TOKEN = ""
MAX_RETRIES = int(os.environ.get("SHIM_RETRIES", "10"))
RETRY_SLEEP = float(os.environ.get("SHIM_RETRY_SLEEP", "2.0"))
HOP_HEADERS = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailers", "transfer-encoding", "upgrade", "host",
    "content-length",
}


def load_token():
    global TOKEN
    env_path = os.environ.get("SHIM_ENV")
    if env_path:
        with open(env_path) as f:
            for line in f:
                line = line.strip()
                if line.startswith("ANTHROPIC_AUTH_TOKEN="):
                    TOKEN = line.split("=", 1)[1]
                    return
    raise SystemExit("no ANTHROPIC_AUTH_TOKEN found")


class Proxy(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[shim] %s %s -> %s\n" % (self.command, self.path, fmt % args))

    def _forward(self, body):
        attempt = 0
        while True:
            attempt += 1
            try:
                ctx = ssl.create_default_context()
                conn = http.client.HTTPSConnection(UPSTREAM_HOST, UPSTREAM_PORT, context=ctx, timeout=600)
                headers = {}
                for k, v in self.headers.items():
                    lk = k.lower()
                    if lk in HOP_HEADERS:
                        continue
                    headers[k] = v
                headers["x-api-key"] = TOKEN
                headers["Authorization"] = "Bearer " + TOKEN
                headers["Host"] = UPSTREAM_HOST
                conn.request(self.command, self.path, body=body, headers=headers)
                resp = conn.getresponse()
                ctype = resp.getheader("content-type", "")
                data = resp.read()
                status = resp.status
                conn.close()
                if ("text/html" in ctype or data[:15].lstrip().lower().startswith(b"<!doctype html")) and attempt < MAX_RETRIES:
                    sys.stderr.write("[shim] HTML flap on %s (attempt %d), retrying\n" % (self.path, attempt))
                    time.sleep(RETRY_SLEEP)
                    continue
                out_headers = {}
                for k, v in resp.getheaders():
                    lk = k.lower()
                    if lk in HOP_HEADERS or lk == "content-type" and False:
                        continue
                    out_headers[k] = v
                out_headers["Content-Length"] = str(len(data))
                self.send_response(status)
                for k, v in out_headers.items():
                    self.send_header(k, v)
                self.end_headers()
                self.wfile.write(data)
                sys.stderr.write("[shim] %s %s -> %d (%d bytes, attempt %d)\n" % (self.command, self.path, status, len(data), attempt))
            except Exception as exc:
                if attempt < MAX_RETRIES:
                    sys.stderr.write("[shim] error %s (attempt %d), retrying\n" % (exc, attempt))
                    time.sleep(RETRY_SLEEP)
                    continue
                sys.stderr.write("[shim] giving up after %d attempts: %s\n" % (attempt, exc))
                try:
                    self.send_error(502, str(exc))
                except Exception:
                    pass
                return
            return

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0") or 0)
        body = self.rfile.read(length) if length else None
        self._forward(body)

    def do_GET(self):
        self._forward(None)

    def do_DELETE(self):
        length = int(self.headers.get("Content-Length", "0") or 0)
        body = self.rfile.read(length) if length else None
        self._forward(body)

    def do_PUT(self):
        length = int(self.headers.get("Content-Length", "0") or 0)
        body = self.rfile.read(length) if length else None
        self._forward(body)


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


if __name__ == "__main__":
    load_token()
    srv = Server(("127.0.0.1", LISTEN_PORT), Proxy)
    sys.stderr.write("[shim] listening on 127.0.0.1:%d -> https://%s (retries=%d)\n" % (LISTEN_PORT, UPSTREAM_HOST, MAX_RETRIES))
    srv.serve_forever()
