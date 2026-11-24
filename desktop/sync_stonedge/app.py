"""Arranque da app de PC: liga o cliente, os alertas, o histórico e a bandeja."""
from __future__ import annotations

import argparse
import asyncio
import logging
import socket
import sys
import threading
import time

from . import APP_NAME, __version__
from .client import PhoneClient
from .config import Config, setup_logging
from .history import History
from .notifier import Notifier

log = logging.getLogger("sync_stonedge")

SINGLE_INSTANCE_PORT = 47801


class Core:
    """Lógica comum ao modo gráfico e ao modo consola."""

    def __init__(self, cfg: Config, history: History, notifier: Notifier, ask_pin_ui, on_status_ui) -> None:
        self.cfg = cfg
        self.history = history
        self.notifier = notifier
        self.ask_pin_ui = ask_pin_ui
        self.on_status_ui = on_status_ui
        self.loop = asyncio.new_event_loop()
        self.client: PhoneClient | None = None
        self._replays: list[dict] = []
        self._replay_task: asyncio.Task | None = None

    # ---- thread asyncio --------------------------------------------------------

    def start(self) -> threading.Thread:
        t = threading.Thread(target=self._run, name="asyncio", daemon=True)
        t.start()
        return t

    def _run(self) -> None:
        asyncio.set_event_loop(self.loop)
        self.client = PhoneClient(self.cfg, self._on_event, self.on_status_ui, self._ask_pin)
        self.loop.run_until_complete(self.client.run())

    def submit(self, fn) -> None:
        self.loop.call_soon_threadsafe(fn)

    async def _ask_pin(self, host: str, fp: str, hint: str) -> str | None:
        fut = self.loop.create_future()

        def done(value: str | None) -> None:
            self.loop.call_soon_threadsafe(lambda: fut.done() or fut.set_result(value))

        self.ask_pin_ui(host, fp, hint, done)
        return await fut

    # ---- eventos do telemóvel -----------------------------------------------------

    def _on_event(self, msg: dict) -> None:
        t = msg.get("t")
        if t == "welcome":
            self._replays.clear()
            return
        if t == "removed":
            self.history.mark_removed(msg.get("id", ""))
            return
        if t != "notif":
            return

        pkg = msg.get("pkg", "")
        if msg.get("icon"):
            self.notifier.store_app_icon(pkg, msg["icon"])
        image = self.notifier.store_image(msg["image"]) if msg.get("image") else None

        item = {k: msg.get(k) for k in ("id", "pkg", "app", "title", "text", "sub", "time", "hidden")}
        item["time"] = item.get("time") or int(time.time() * 1000)
        is_new = self.history.add(item)

        if msg.get("replay"):
            # notificações já existentes quando o PC se liga: um só alerta-resumo
            if is_new:
                self._replays.append(item)
            if self._replay_task is None or self._replay_task.done():
                self._replay_task = asyncio.ensure_future(self._replay_summary())
            return

        if self.cfg.get("dnd") or not is_new:
            return
        app = msg.get("app") or pkg
        title = msg.get("title") or app
        head = app if title == app else f"{app} · {title}"
        text = msg.get("text") or ""
        if msg.get("sub"):
            text = f"{text}\n{msg['sub']}" if text else msg["sub"]
        asyncio.ensure_future(self.notifier.show(head, text, image or self.notifier.app_icon_path(pkg)))

    async def _replay_summary(self) -> None:
        await asyncio.sleep(1.5)
        n = len(self._replays)
        apps = sorted({r.get("app") or "" for r in self._replays})
        self._replays.clear()
        if n and not self.cfg.get("dnd"):
            await self.notifier.show(
                f"{n} notificação(ões) por ver no telemóvel",
                ", ".join(a for a in apps if a)[:200] or "Abre o histórico para ver",
            )

    # ---- ações ----------------------------------------------------------------------

    def pair_again(self) -> None:
        if self.client:
            self.submit(self.client.request_pairing)

    def test(self) -> None:
        self.submit(lambda: asyncio.ensure_future(
            self.notifier.show(f"{APP_NAME} · teste", "Os alertas deste PC estão a funcionar.")
        ))

    def stop(self) -> None:
        if self.client:
            self.submit(self.client.stop)


def _single_instance() -> socket.socket | None:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.bind(("127.0.0.1", SINGLE_INSTANCE_PORT))
        s.listen(1)
        return s
    except OSError:
        return None


def run_gui(cfg: Config) -> None:
    from . import autostart
    from .tray import Tray
    from .ui import UI

    history = History()
    ui = UI(history)
    notifier = Notifier(on_click=ui.show_history)
    notifier.fallback = ui.toast
    tray: Tray | None = None

    def on_status(state: str, text: str) -> None:
        if tray:
            tray.set_status(state, text)
        if state == "cert_changed":
            ui.info(APP_NAME, text)

    core = Core(cfg, history, notifier, ui.ask_pin, on_status)

    def toggle_dnd() -> None:
        cfg.set(dnd=not cfg.get("dnd"))

    def toggle_autostart() -> None:
        try:
            autostart.set_enabled(not autostart.is_enabled())
        except Exception:
            log.exception("autostart")

    def quit_app() -> None:
        core.stop()
        if tray:
            tray.stop()
        ui.quit()

    tray = Tray(
        actions={
            "history": ui.show_history,
            "toggle_dnd": toggle_dnd,
            "toggle_autostart": toggle_autostart,
            "pair": core.pair_again,
            "test": core.test,
            "quit": quit_app,
        },
        is_dnd=lambda: bool(cfg.get("dnd")),
        is_autostart=autostart.is_enabled,
    )
    tray.start()
    core.start()
    ui.mainloop()


def run_console(cfg: Config, pin: str | None) -> None:
    history = History()
    notifier = Notifier(on_click=lambda: None)
    notifier.fallback = lambda t, x, _i: print(f"\n🔔 {t}\n   {x}\n", flush=True)
    # em modo consola os alertas são sempre impressos (sem D-Bus/WinRT)
    notifier._init_done = True

    def ask_pin(host: str, fp: str, hint: str, done) -> None:
        if hint:
            print(f"! {hint}")
        print(f"Telemóvel em {host} · certificado {fp[:16]}")
        value = pin or input("Código de emparelhamento (6 dígitos): ").strip()
        done(value or None)

    core = Core(cfg, history, notifier, ask_pin, lambda s, t: None)  # o logging já imprime o estado
    t = core.start()
    try:
        while t.is_alive():
            t.join(0.5)
    except KeyboardInterrupt:
        core.stop()


def main() -> None:
    parser = argparse.ArgumentParser(prog="sync_stonedge", description=f"{APP_NAME} {__version__}")
    parser.add_argument("--console", action="store_true", help="sem interface gráfica (registo no terminal)")
    parser.add_argument("--host", help="IP do telemóvel (salta a descoberta)")
    parser.add_argument("--port", type=int, help="porta do telemóvel (por omissão 47800)")
    parser.add_argument("--pin", help="código de emparelhamento (modo consola)")
    parser.add_argument("--config", help="ficheiro de configuração alternativo")
    args = parser.parse_args()

    setup_logging(console=args.console)
    from pathlib import Path

    cfg = Config(Path(args.config) if args.config else None)
    if args.host:
        cfg.set(host=args.host)
    if args.port:
        cfg.set(port=args.port)

    lock = _single_instance()
    if lock is None and not args.config:
        print(f"{APP_NAME} já está a correr.")
        sys.exit(0)

    log.info("%s %s a arrancar", APP_NAME, __version__)
    if args.console:
        run_console(cfg, args.pin)
    else:
        run_gui(cfg)


if __name__ == "__main__":
    main()
