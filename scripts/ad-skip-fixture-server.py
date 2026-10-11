import argparse
import hashlib
import hmac
import json
import ssl
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
from xml.sax.saxutils import escape


def handler(directory, base_url, token):
    audio = (directory / "seek-fixture.wav").read_bytes()
    result = json.loads((directory / "result.json").read_text())
    digest = hashlib.sha256(audio).hexdigest()
    if result["media_sha256"] != digest:
        raise ValueError("Fixture identity mismatch")
    jobs = {}
    keys = {}

    class FixtureHandler(BaseHTTPRequestHandler):
        def log_message(self, format_string, *args):
            pass

        def respond(self, code, value, content_type="application/json"):
            body = json.dumps(value).encode() if isinstance(value, dict) else value
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def authorized(self):
            return hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + token)

        def status(self, job_id):
            return {"job_id": job_id, "status": jobs[job_id]["status"], "media_sha256": digest,
                    "stage": "controlled_fixture", "source_job_id": job_id, "passage_job_id": job_id,
                    "created": jobs[job_id]["created"], "updated": time.time(), "expires_at": 4070908800,
                    "cancel_requested": jobs[job_id]["cancel_requested"], "runtime_identity": {"fixture": True}}

        def do_GET(self):
            path = urlsplit(self.path).path
            if path == "/feed.xml":
                enclosure = escape(base_url + "/seek-fixture.wav", {'"': "&quot;"})
                feed = (f'<rss version="2.0"><channel><title>Controlled seek fixture</title>'
                        f'<link>{escape(base_url)}</link><description>Synthetic tone; no inference.</description>'
                        f'<item><title>16 second seek fixture</title><guid>controlled-seek-fixture-v1</guid>'
                        f'<description>Controlled ranges, not detector evidence.</description>'
                        f'<enclosure url="{enclosure}" length="{len(audio)}" type="audio/wav"/>'
                        '</item></channel></rss>').encode()
                self.respond(200, feed, "application/rss+xml")
                return
            if path == "/seek-fixture.wav":
                self.respond(200, audio, "audio/wav")
                return
            if not self.authorized():
                self.respond(401, {})
                return
            parts = path.strip("/").split("/")
            if len(parts) not in (3, 4) or parts[:2] != ["v1", "analyses"] or parts[2] not in jobs:
                self.respond(404, {})
                return
            job_id = parts[2]
            job = jobs[job_id]
            if len(parts) == 4:
                if parts[3] != "result" or job["status"] != "completed":
                    self.respond(409, {})
                    return
                self.respond(200, dict(result, job_id=job_id))
                return
            if job["status"] in ("queued", "running"):
                job["polls"] += 1
                job["status"] = "completed" if job["polls"] >= 2 else "running"
            self.respond(200, self.status(job_id))

        def do_POST(self):
            self.connection.settimeout(120)
            if not self.authorized():
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                except ValueError:
                    length = 0
                if 0 < length <= len(audio):
                    self.rfile.read(length)
                self.respond(401, {})
                return
            url = urlsplit(self.path)
            if url.path == "/v1/analyses/upload":
                key = self.headers.get("Idempotency-Key", "")
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                except ValueError:
                    length = 0
                if length != len(audio) or not key or len(key) > 256:
                    self.respond(422, {})
                    return
                if (parse_qs(url.query).get("expected_sha256") != [digest]
                        or hashlib.sha256(self.rfile.read(length)).hexdigest() != digest):
                    self.respond(409, {})
                    return
                job_id = keys.setdefault(key, "fixture-" + hashlib.sha256(key.encode()).hexdigest()[:24])
                jobs.setdefault(job_id, {"status": "queued", "polls": 0, "created": time.time(),
                                         "cancel_requested": False})
                self.respond(202, self.status(job_id))
                return
            parts = url.path.strip("/").split("/")
            if len(parts) != 4 or parts[:2] != ["v1", "analyses"] or parts[2] not in jobs:
                self.respond(404, {})
                return
            job_id = parts[2]
            job = jobs[job_id]
            if parts[3] == "cancel":
                job["cancel_requested"] = True
                if job["status"] != "completed":
                    job["status"] = "cancelled"
            elif parts[3] == "retry" and job["status"] == "cancelled":
                job.update(status="queued", polls=0, cancel_requested=False)
            else:
                self.respond(409, {})
                return
            self.respond(202, self.status(job_id))

    return FixtureHandler


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--cert", type=Path, required=True)
    parser.add_argument("--key", type=Path, required=True)
    parser.add_argument("--token-file", type=Path, required=True)
    args = parser.parse_args()
    origin = urlsplit(args.base_url)
    if (origin.scheme != "https" or not origin.hostname or origin.username or origin.password
            or origin.path not in ("", "/") or origin.query or origin.fragment):
        parser.error("A certificate-valid HTTPS origin is required")
    if args.token_file.stat().st_mode & 0o077:
        parser.error("The fixture token file must be private (mode 0600)")
    token = args.token_file.read_text().strip()
    if not token or "\n" in token or "\r" in token:
        parser.error("Invalid fixture token file")
    server = HTTPServer((args.bind, args.port), handler(args.directory, args.base_url.rstrip("/"), token))
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(args.cert, args.key)
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    print(args.base_url.rstrip("/") + "/feed.xml", flush=True)
    server.serve_forever()
