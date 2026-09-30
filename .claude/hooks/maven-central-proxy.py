#!/usr/bin/env python3
"""Local caching proxy for Maven Central, used only in Claude Code on the web.

Shared cloud containers get HTTP 429 (Too Many Requests) from Maven Central when Gradle
resolves many artifacts at once. This proxy serves http://127.0.0.1:<port>/maven2/<path>,
caches every file on disk and retries 429s and network errors with backoff across mirrors.

Usage: maven-central-proxy.py <port> <cache-dir>
"""
import http.server
import os
import random
import socketserver
import sys
import time
import urllib.error
import urllib.request

MIRRORS = [
    "https://repo1.maven.org/maven2/",
    "https://repo.maven.apache.org/maven2/",
    "https://maven-central-eu.storage-download.googleapis.com/maven2/",
]
PORT = int(sys.argv[1])
CACHE = sys.argv[2]


def fetch(path):
    if ".." in path.split("/"):
        return 404, b""
    cached = os.path.join(CACHE, path)
    if os.path.isfile(cached):
        with open(cached, "rb") as f:
            return 200, f.read()
    if os.path.isfile(cached + ".404"):
        return 404, b""
    not_found = 0
    for attempt in range(40):
        mirror = MIRRORS[attempt % len(MIRRORS)]
        request = urllib.request.Request(mirror + path, headers={"User-Agent": "Gradle"})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                data = response.read()
            os.makedirs(os.path.dirname(cached), exist_ok=True)
            tmp = "%s.tmp%d" % (cached, random.randint(0, 1 << 30))
            with open(tmp, "wb") as f:
                f.write(data)
            os.replace(tmp, cached)
            return 200, data
        except urllib.error.HTTPError as e:
            if e.code == 404:
                not_found += 1
                # Maven Central itself is authoritative; the other mirrors may lag behind.
                if not_found >= 2 or mirror.startswith("https://repo1."):
                    os.makedirs(os.path.dirname(cached), exist_ok=True)
                    open(cached + ".404", "w").close()
                    return 404, b""
                continue
            problem = "HTTP %d" % e.code
        except Exception as e:
            problem = type(e).__name__
        print("retry %d %s: %s" % (attempt + 1, mirror + path, problem), file=sys.stderr, flush=True)
        time.sleep(min(1 + attempt * 0.7, 10))
    print("gave up: %s" % path, file=sys.stderr, flush=True)
    return 502, b""


class Handler(http.server.BaseHTTPRequestHandler):
    def _serve(self, with_body):
        path = self.path.split("?")[0]
        if not path.startswith("/maven2/"):
            self.send_response(404)
            self.end_headers()
            return
        code, data = fetch(path[len("/maven2/"):])
        self.send_response(code)
        self.send_header("Content-Length", str(len(data) if code == 200 else 0))
        self.end_headers()
        if with_body and code == 200:
            self.wfile.write(data)

    def do_GET(self):
        self._serve(True)

    def do_HEAD(self):
        self._serve(False)

    def log_message(self, *args):
        pass


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True


Server(("127.0.0.1", PORT), Handler).serve_forever()
