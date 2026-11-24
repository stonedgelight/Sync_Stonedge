"""Cliente WSS que se liga ao telemóvel e recebe as notificações.

O telemóvel é o servidor; o PC liga-se a ele. Assim o PC só faz ligações de saída e não é
preciso abrir portas na firewall (o hotspot costuma ser classificado como rede "Pública").

Segurança:
- TLS com o certificado auto-assinado do telemóvel;
- no emparelhamento o utilizador escreve o PIN de 6 dígitos mostrado no telemóvel e pode
  comparar a impressão digital do certificado; a partir daí o PC fixa essa impressão digital
  e autentica-se com um token aleatório de 256 bits.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import ssl
from typing import Awaitable, Callable

import websockets
from websockets.asyncio.client import connect

from .config import Config
from .discovery import default_gateways, mdns_browse

log = logging.getLogger(__name__)

PAIR_ERRORS = {
    "NO_PIN": "No telemóvel, toca primeiro em «Gerar código de emparelhamento».",
    "EXPIRED": "O código expirou. Gera um novo no telemóvel.",
    "WRONG": "Código errado. Tenta outra vez.",
    "LOCKED": "Demasiadas tentativas. Gera um código novo no telemóvel.",
}


class CertificateChanged(Exception):
    pass


class PairingCancelled(Exception):
    pass


StatusCb = Callable[[str, str], None]                 # (estado, texto)
EventCb = Callable[[dict], None]
AskPin = Callable[[str, str, str], Awaitable[str | None]]  # (host, impressão digital, aviso) -> pin


class PhoneClient:
    def __init__(self, cfg: Config, on_event: EventCb, on_status: StatusCb, ask_pin: AskPin) -> None:
        self.cfg = cfg
        self.on_event = on_event
        self.on_status = on_status
        self.ask_pin = ask_pin
        self._stop = asyncio.Event()
        self._pair_requested = asyncio.Event()
        self._wake = asyncio.Event()
        self._ws = None
        self._pair_hint = ""
        self.connected = False

    # ---- controlo (chamar a partir do loop asyncio) -------------------------

    def request_pairing(self) -> None:
        """Esquece o emparelhamento atual e pede um novo PIN."""
        self.cfg.set(token=None, fingerprint=None)
        self._pair_hint = ""
        self._pair_requested.set()
        self._wake.set()
        if self._ws is not None:
            asyncio.ensure_future(self._ws.close())

    async def send(self, msg: dict) -> bool:
        """Envia uma mensagem ao telemóvel; devolve False se não houver sessão ativa."""
        if self._ws is None or not self.connected:
            return False
        try:
            await self._ws.send(json.dumps(msg))
            return True
        except Exception:
            log.warning("Falha a enviar %s", msg.get("t"), exc_info=True)
            return False

    def stop(self) -> None:
        self._stop.set()
        self._wake.set()
        if self._ws is not None:
            asyncio.ensure_future(self._ws.close())

    # ---- ciclo principal -----------------------------------------------------

    async def run(self) -> None:
        if not self.cfg.paired:
            self._pair_requested.set()  # primeira execução: pede logo o PIN
        backoff = 1.0
        while not self._stop.is_set():
            if not self.cfg.paired and not self._pair_requested.is_set():
                self._status("unpaired", "Não emparelhado. Usa «Emparelhar» no menu.")
                await self._sleep(3600)
                continue

            self._status("searching", "À procura do telemóvel…")
            ok = await self._try_hosts(self._quick_candidates())
            if not ok and not self._stop.is_set():
                found = [h for h, _ in await mdns_browse()]
                ok = await self._try_hosts(found)

            if self._stop.is_set():
                break
            if ok:
                backoff = 1.0
            else:
                if self.cfg.paired:
                    self._status("offline", "Telemóvel não encontrado. Nova tentativa em breve.")
                backoff = min(backoff * 2, 30.0)
                await self._sleep(backoff)

    async def _sleep(self, seconds: float) -> None:
        self._wake.clear()
        try:
            await asyncio.wait_for(self._wake.wait(), timeout=seconds)
        except asyncio.TimeoutError:
            pass

    def _quick_candidates(self) -> list[str]:
        hosts: list[str] = []
        if self.cfg.get("host"):
            hosts.append(self.cfg.get("host"))
        hosts += default_gateways()
        seen: set[str] = set()
        return [h for h in hosts if h and not (h in seen or seen.add(h))]

    async def _try_hosts(self, hosts: list[str]) -> bool:
        """Devolve True se chegou a estabelecer sessão com algum host."""
        for host in hosts:
            if self._stop.is_set():
                return False
            try:
                await self._session(host)
                return True
            except CertificateChanged:
                self._status(
                    "cert_changed",
                    "O certificado do telemóvel mudou. Se reinstalaste a app, escolhe «Emparelhar de novo».",
                )
                self.cfg.set(token=None)
                self._pair_requested.clear()
                return True
            except PairingCancelled:
                self._pair_requested.clear()
                return True
            except (OSError, asyncio.TimeoutError, websockets.exceptions.WebSocketException) as e:
                log.info("Sem ligação a %s: %s", host, e)
            except Exception:
                log.exception("Erro inesperado com %s", host)
        return False

    async def _session(self, host: str) -> None:
        port = int(self.cfg.get("port"))
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE  # verificação feita abaixo por impressão digital fixada

        async with connect(
            f"wss://{host}:{port}",
            ssl=ctx,
            open_timeout=4,
            ping_interval=20,
            ping_timeout=20,
            close_timeout=2,
            max_size=16 * 1024 * 1024,
        ) as ws:
            der = ws.transport.get_extra_info("ssl_object").getpeercert(binary_form=True)
            fp = hashlib.sha256(der).hexdigest()
            pinned = self.cfg.get("fingerprint")
            if pinned and pinned != fp:
                raise CertificateChanged(fp)

            self._ws = ws
            try:
                if self.cfg.paired:
                    await ws.send(json.dumps({"t": "hello", "token": self.cfg.get("token"),
                                              "client": self.cfg.get("client_name")}))
                else:
                    self._status("pairing", f"Telemóvel encontrado em {host}. À espera do código…")
                    pin = await self.ask_pin(host, fp, self._pair_hint)
                    if not pin:
                        raise PairingCancelled()
                    await ws.send(json.dumps({"t": "pair", "pin": pin, "client": self.cfg.get("client_name")}))

                async for raw in ws:
                    await self._handle(host, fp, raw)
            finally:
                self._ws = None
                if self.connected:
                    self.connected = False
                    self._status("offline", "Ligação ao telemóvel perdida. A tentar de novo…")

    async def _handle(self, host: str, fp: str, raw: str | bytes) -> None:
        try:
            msg = json.loads(raw)
        except ValueError:
            return
        t = msg.get("t")
        if t == "paired":
            self.cfg.set(token=msg["token"], fingerprint=fp, host=host)
            self._pair_requested.clear()
            self._pair_hint = ""
            log.info("Emparelhado com %s", host)
        elif t == "pair_failed":
            self._pair_hint = PAIR_ERRORS.get(msg.get("reason", ""), "Emparelhamento recusado.")
            self._status("pairing", self._pair_hint)
            await self._ws.close()
        elif t == "unauthorized":
            # o telemóvel esqueceu este PC: volta a pedir PIN
            self.cfg.set(token=None, fingerprint=None)
            self._pair_hint = "O telemóvel já não reconhece este PC. Gera um código novo."
            self._pair_requested.set()
        elif t == "welcome":
            self.connected = True
            device = msg.get("device") or "telemóvel"
            self.cfg.set(host=host, device=device)
            self._status("connected", f"Ligado a {device} (Android {msg.get('android', '?')})")
            self.on_event(msg)
        elif t in ("notif", "removed", "dial_ack", "call_state"):
            self.on_event(msg)

    def _status(self, state: str, text: str) -> None:
        log.info("[%s] %s", state, text)
        try:
            self.on_status(state, text)
        except Exception:
            log.exception("on_status falhou")
