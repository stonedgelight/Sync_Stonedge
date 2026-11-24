"""Descoberta do telemóvel na rede local.

Ordem de tentativa:
1. último IP conhecido;
2. gateway por omissão (quando o PC está ligado ao hotspot do telemóvel, o gateway É o telemóvel);
3. mDNS/DNS-SD (_syncstonedge._tcp), útil quando ambos estão num router Wi-Fi.
"""
from __future__ import annotations

import asyncio
import logging
import re
import subprocess
import sys

log = logging.getLogger(__name__)
SERVICE_TYPE = "_syncstonedge._tcp.local."


def _run(cmd: list[str]) -> str:
    kwargs = {}
    if sys.platform == "win32":
        kwargs["creationflags"] = 0x08000000  # CREATE_NO_WINDOW
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=5, **kwargs).stdout
    except Exception:
        log.debug("Falhou: %s", cmd, exc_info=True)
        return ""


def default_gateways() -> list[str]:
    """Gateways IPv4 por omissão, independentemente do idioma do sistema."""
    gws: list[str] = []
    if sys.platform == "win32":
        out = _run(["route", "print", "-4", "0.0.0.0"])
        for m in re.finditer(r"^\s*0\.0\.0\.0\s+0\.0\.0\.0\s+(\d+\.\d+\.\d+\.\d+)\s", out, re.M):
            gws.append(m.group(1))
    else:
        out = _run(["ip", "-4", "route", "show", "default"])
        gws += re.findall(r"via (\d+\.\d+\.\d+\.\d+)", out)
    seen: set[str] = set()
    return [g for g in gws if not (g in seen or seen.add(g))]


async def mdns_browse(timeout: float = 3.0) -> list[tuple[str, int]]:
    try:
        from zeroconf import ServiceStateChange
        from zeroconf.asyncio import AsyncServiceBrowser, AsyncServiceInfo, AsyncZeroconf
    except ImportError:
        return []

    names: set[str] = set()

    def handler(zeroconf, service_type, name, state_change):  # noqa: ANN001
        if state_change is ServiceStateChange.Added:
            names.add(name)

    found: list[tuple[str, int]] = []
    azc = AsyncZeroconf()
    try:
        browser = AsyncServiceBrowser(azc.zeroconf, [SERVICE_TYPE], handlers=[handler])
        await asyncio.sleep(timeout)
        for name in names:
            info = AsyncServiceInfo(SERVICE_TYPE, name)
            if await info.async_request(azc.zeroconf, 2000):
                for addr in info.parsed_addresses():
                    if ":" not in addr:
                        found.append((addr, info.port or 0))
        await browser.async_cancel()
    except Exception:
        log.warning("mDNS indisponível", exc_info=True)
    finally:
        await azc.async_close()
    return found
