"""Simulador do telemóvel para testar a app de PC sem Android.

Implementa o mesmo protocolo da app Android: servidor WSS com certificado auto-assinado,
emparelhamento por PIN, token, notificações e remoções.

    pip install websockets cryptography
    python tools/fake_phone.py --port 47800 --every 5

Depois, noutro terminal:  python -m sync_stonedge --console --host 127.0.0.1
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import datetime as dt
import hashlib
import io
import json
import secrets
import ssl
import tempfile
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID
from websockets.asyncio.server import serve


def make_cert(directory: Path) -> tuple[Path, Path, str]:
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Sync_Stonedge simulador")])
    now = dt.datetime.now(dt.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now - dt.timedelta(days=1))
            .not_valid_after(now + dt.timedelta(days=3650)).sign(key, hashes.SHA256()))
    cert_p, key_p = directory / "cert.pem", directory / "key.pem"
    cert_p.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key_p.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                        serialization.NoEncryption()))
    fp = hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()
    return cert_p, key_p, fp


def tiny_png() -> str:
    try:
        from PIL import Image
        buf = io.BytesIO()
        Image.new("RGBA", (96, 96), (56, 217, 169, 255)).save(buf, "PNG")
        return base64.b64encode(buf.getvalue()).decode()
    except ImportError:
        return ""


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=47800)
    ap.add_argument("--every", type=float, default=5.0, help="segundos entre notificações falsas")
    ap.add_argument("--count", type=int, default=0, help="parar após N notificações (0 = infinito)")
    args = ap.parse_args()

    tmp = Path(tempfile.mkdtemp())
    cert, key, fp = make_cert(tmp)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert, key)

    pin = f"{secrets.randbelow(1_000_000):06d}"
    tokens: set[str] = set()
    clients: set = set()
    icon = tiny_png()
    print(f"Simulador na porta {args.port} · PIN {pin} · certificado {fp[:16]}", flush=True)

    async def handler(ws):
        authed = False
        try:
            async for raw in ws:
                msg = json.loads(raw)
                if msg.get("t") == "hello" and msg.get("token") in tokens:
                    authed = True
                elif msg.get("t") == "pair":
                    if msg.get("pin") == pin:
                        tok = secrets.token_urlsafe(32)
                        tokens.add(tok)
                        await ws.send(json.dumps({"t": "paired", "token": tok}))
                        authed = True
                    else:
                        await ws.send(json.dumps({"t": "pair_failed", "reason": "WRONG"}))
                        continue
                elif msg.get("t") == "hello":
                    await ws.send(json.dumps({"t": "unauthorized"}))
                    await ws.close()
                    return
                if authed and ws not in clients:
                    clients.add(ws)
                    await ws.send(json.dumps({"t": "welcome", "device": "Simulador", "android": "14", "app": "sim"}))
                    await ws.send(json.dumps({"t": "notif", "id": "old-1", "pkg": "com.whatsapp", "app": "WhatsApp",
                                              "title": "Mãe", "text": "Já chegaste?", "time": 0, "replay": True,
                                              "icon": icon}))
        finally:
            clients.discard(ws)

    async def producer():
        n = 0
        while args.count == 0 or n < args.count:
            await asyncio.sleep(args.every)
            n += 1
            msg = {"t": "notif", "id": f"k{n}", "pkg": "com.google.android.apps.messaging", "app": "Mensagens",
                   "title": "Contacto de teste", "text": f"Mensagem número {n}", "replay": False,
                   "time": int(dt.datetime.now().timestamp() * 1000)}
            for c in list(clients):
                await c.send(json.dumps(msg))
            if n % 3 == 0:
                for c in list(clients):
                    await c.send(json.dumps({"t": "removed", "id": f"k{n - 1}"}))

    async with serve(handler, "0.0.0.0", args.port, ssl=ctx):
        await producer()
        await asyncio.sleep(1)


if __name__ == "__main__":
    asyncio.run(main())
