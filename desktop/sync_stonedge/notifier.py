"""Alertas nativos: notificações do Windows 11 (WinRT) ou do Linux (D-Bus/libnotify).

Usa a biblioteca desktop-notifier, que no Windows regista um AppUserModelID próprio para
os alertas ficarem guardados no Centro de Notificações. Se o sistema não suportar alertas
nativos, recorre a um pop-up próprio (ver ui.py).
"""
from __future__ import annotations

import base64
import hashlib
import logging
from pathlib import Path
from typing import Callable

from . import APP_NAME
from .config import data_dir, resource_path

log = logging.getLogger(__name__)


class Notifier:
    def __init__(self, on_click: Callable[[], None]) -> None:
        self.on_click = on_click
        self.fallback: Callable[[str, str, str | None], None] | None = None
        self._dn = None
        self._init_done = False
        self.icons = data_dir() / "icons"

    def _lazy_init(self) -> None:
        """Criado dentro do loop asyncio (requisito do WinRT e do D-Bus)."""
        if self._init_done:
            return
        self._init_done = True
        try:
            from desktop_notifier import DesktopNotifier, Icon

            icon = resource_path("icon.png")
            self._dn = DesktopNotifier(app_name=APP_NAME, app_icon=Icon(path=icon) if icon.exists() else None)
        except Exception:
            log.warning("Alertas nativos indisponíveis; a usar pop-up próprio", exc_info=True)
            self._dn = None

    # ---- ícones --------------------------------------------------------------

    def store_app_icon(self, pkg: str, b64: str) -> None:
        try:
            (self.icons / f"{_safe(pkg)}.png").write_bytes(base64.b64decode(b64))
        except Exception:
            log.debug("ícone inválido para %s", pkg)

    def app_icon_path(self, pkg: str) -> str | None:
        p = self.icons / f"{_safe(pkg)}.png"
        return str(p) if p.exists() else None

    def store_image(self, b64: str) -> str | None:
        try:
            raw = base64.b64decode(b64)
            p = self.icons / f"img_{hashlib.sha1(raw).hexdigest()[:16]}.png"
            if not p.exists():
                p.write_bytes(raw)
                self._prune_images()
            return str(p)
        except Exception:
            return None

    def _prune_images(self, keep: int = 80) -> None:
        imgs = sorted(self.icons.glob("img_*.png"), key=lambda f: f.stat().st_mtime, reverse=True)
        for old in imgs[keep:]:
            try:
                old.unlink()
            except OSError:
                pass

    # ---- mostrar ---------------------------------------------------------------

    async def show(self, title: str, text: str, icon_path: str | None = None) -> None:
        self._lazy_init()
        title = title[:120]
        text = text[:600]
        if self._dn is not None:
            try:
                from desktop_notifier import Icon

                await self._dn.send(
                    title=title,
                    message=text,
                    icon=Icon(path=Path(icon_path)) if icon_path else None,
                    on_clicked=self.on_click,
                )
                return
            except Exception:
                log.warning("Falha no alerta nativo; a usar pop-up", exc_info=True)
        if self.fallback is not None:
            self.fallback(title, text, icon_path)


def _safe(name: str) -> str:
    return "".join(c if c.isalnum() or c in "._-" else "_" for c in name)
