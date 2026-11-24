"""Ícone na área de notificação (bandeja) com o estado e o menu da app."""
from __future__ import annotations

import logging
import threading
from typing import Callable

from PIL import Image, ImageDraw

from . import APP_NAME

log = logging.getLogger(__name__)

COLORS = {
    "connected": "#38D9A9",
    "pairing": "#F2B705",
    "searching": "#9AA5A8",
    "offline": "#9AA5A8",
    "unpaired": "#F2B705",
    "cert_changed": "#E5484D",
}


def make_icon(state: str, size: int = 64) -> Image.Image:
    """Desenha o logótipo: círculo escuro, telemóvel branco e ondas com a cor do estado."""
    s = size / 108
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([4 * s, 4 * s, 104 * s, 104 * s], fill="#0E2A33")
    d.rounded_rectangle([26 * s, 28 * s, 56 * s, 80 * s], radius=5 * s, outline="white", width=max(2, int(5 * s)))
    wave = COLORS.get(state, "#9AA5A8")
    w = max(2, int(5 * s))
    d.arc([50 * s, 40 * s, 78 * s, 68 * s], start=-60, end=60, fill=wave, width=w)
    d.arc([46 * s, 28 * s, 98 * s, 80 * s], start=-55, end=55, fill=wave, width=w)
    d.rectangle([37 * s, 40 * s, 45 * s, 48 * s], fill=wave)
    return img


class Tray:
    def __init__(self, actions: dict[str, Callable[[], None]], is_dnd: Callable[[], bool],
                 is_autostart: Callable[[], bool]) -> None:
        import pystray

        self._pystray = pystray
        self.actions = actions
        self.status_text = "A iniciar…"
        self.state = "searching"
        M = pystray.MenuItem
        menu = pystray.Menu(
            M(lambda _i: self.status_text, None, enabled=False),
            pystray.Menu.SEPARATOR,
            M("Histórico de notificações", lambda: actions["history"](), default=True),
            M("Não incomodar", lambda: actions["toggle_dnd"](), checked=lambda _i: is_dnd()),
            M("Arrancar com o sistema", lambda: actions["toggle_autostart"](), checked=lambda _i: is_autostart()),
            M("Emparelhar de novo…", lambda: actions["pair"]()),
            M("Notificação de teste no PC", lambda: actions["test"]()),
            pystray.Menu.SEPARATOR,
            M("Sair", lambda: actions["quit"]()),
        )
        self.icon = pystray.Icon(APP_NAME, make_icon(self.state), APP_NAME, menu)

    def start(self) -> None:
        threading.Thread(target=self.icon.run, name="tray", daemon=True).start()

    def set_status(self, state: str, text: str) -> None:
        self.state = state
        self.status_text = text
        try:
            self.icon.icon = make_icon(state)
            self.icon.title = f"{APP_NAME} · {text}"[:120]
            self.icon.update_menu()
        except Exception:
            log.debug("tray ainda não pronto", exc_info=True)

    def stop(self) -> None:
        try:
            self.icon.stop()
        except Exception:
            pass
