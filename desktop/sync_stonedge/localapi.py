"""API local para outras aplicações do PC (ex.: Motor comercial) usarem o telemóvel.

Escuta só em 127.0.0.1 e exige o token guardado em <dados>/api.json, que só o utilizador
do Windows consegue ler. Contrato completo em docs/INTEGRACAO_MOTOR_COMERCIAL.md.

    GET  /api/status          -> {ok, connected, device, call_state, api_version}
    POST /api/dial            {number, name?, lead_id?} -> {ok, req, via, error?}
    GET  /api/calls?since=N   -> {ok, events: [{seq, type, ts, ...}], last_seq}
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import TYPE_CHECKING
from urllib.parse import parse_qs, urlparse

from . import __version__
from .config import data_dir

if TYPE_CHECKING:
    from .app import Core

log = logging.getLogger(__name__)
API_VERSION = 1
DEFAULT_PORT = 47802


class LocalAPI:
    def __init__(self, core: "Core", port: int = DEFAULT_PORT) -> None:
        self.core = core
        self.token = secrets.token_urlsafe(24)
        self.server = self._bind(port)
        self.port = self.server.server_address[1]
        self.file = data_dir() / "api.json"

    def _bind(self, port: int) -> ThreadingHTTPServer:
        api = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, fmt, *args):  # noqa: A003
                log.debug("api: " + fmt, *args)

            def _send(self, code: int, body: dict) -> None:
                raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
                self.send_response(code)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def _authorized(self) -> bool:
                if secrets.compare_digest(self.headers.get("X-Sync-Token", ""), api.token):
                    return True
                self._send(401, {"ok": False, "error": "token inválido"})
                return False

            def do_GET(self):  # noqa: N802
                if not self._authorized():
                    return
                url = urlparse(self.path)
                if url.path == "/api/status":
                    self._send(200, api.core.api_status() | {"api_version": API_VERSION, "app": __version__})
                elif url.path == "/api/calls":
                    since = int((parse_qs(url.query).get("since") or ["0"])[0] or 0)
                    self._send(200, api.core.api_calls(since))
                else:
                    self._send(404, {"ok": False, "error": "não existe"})

            def do_POST(self):  # noqa: N802
                if not self._authorized():
                    return
                if urlparse(self.path).path != "/api/dial":
                    self._send(404, {"ok": False, "error": "não existe"})
                    return
                try:
                    n = int(self.headers.get("Content-Length") or 0)
                    body = json.loads(self.rfile.read(min(n, 65536)) or b"{}")
                except ValueError:
                    self._send(400, {"ok": False, "error": "JSON inválido"})
                    return
                number = str(body.get("number") or "").strip()
                if not any(c.isdigit() for c in number):
                    self._send(400, {"ok": False, "error": "número em falta"})
                    return
                fut = asyncio.run_coroutine_threadsafe(
                    api.core.dial(number, str(body.get("name") or ""), body.get("lead_id")), api.core.loop)
                try:
                    self._send(200, fut.result(timeout=10))
                except Exception as e:
                    self._send(504, {"ok": False, "error": f"sem resposta do telemóvel ({e.__class__.__name__})"})

        try:
            return ThreadingHTTPServer(("127.0.0.1", port), Handler)
        except OSError:
            log.warning("Porta %s ocupada; a usar uma porta livre", port)
            return ThreadingHTTPServer(("127.0.0.1", 0), Handler)

    def start(self) -> None:
        tmp = self.file.with_suffix(".tmp")
        tmp.write_text(json.dumps({"port": self.port, "token": self.token, "pid": os.getpid(),
                                   "api_version": API_VERSION}), encoding="utf-8")
        tmp.replace(self.file)
        threading.Thread(target=self.server.serve_forever, name="localapi", daemon=True).start()
        log.info("API local em 127.0.0.1:%s", self.port)

    def stop(self) -> None:
        try:
            self.server.shutdown()
            if self.file.exists():
                data = json.loads(self.file.read_text(encoding="utf-8"))
                if data.get("pid") == os.getpid():
                    self.file.unlink()
        except Exception:
            pass
