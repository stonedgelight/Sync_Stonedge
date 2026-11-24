"""Configuração persistente e pastas de dados."""
from __future__ import annotations

import json
import logging
import os
import socket
import sys
import threading
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Any

from . import APP_NAME

DEFAULT_PORT = 47800


def data_dir() -> Path:
    if sys.platform == "win32":
        base = Path(os.environ.get("APPDATA") or Path.home() / "AppData" / "Roaming")
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config")
    d = base / APP_NAME
    (d / "icons").mkdir(parents=True, exist_ok=True)
    return d


def resource_path(name: str) -> Path:
    """Caminho de um recurso (funciona também dentro do executável PyInstaller)."""
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
    return base / "assets" / name


def setup_logging(console: bool = False) -> None:
    handlers: list[logging.Handler] = [
        RotatingFileHandler(data_dir() / "sync_stonedge.log", maxBytes=1_000_000, backupCount=2, encoding="utf-8")
    ]
    if console:
        handlers.append(logging.StreamHandler(sys.stdout))
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        handlers=handlers,
    )


DEFAULTS: dict[str, Any] = {
    "host": None,           # último IP onde o telemóvel respondeu
    "port": DEFAULT_PORT,
    "token": None,          # segredo entregue pelo telemóvel no emparelhamento
    "fingerprint": None,    # SHA-256 do certificado TLS do telemóvel (fixado)
    "device": None,         # nome do telemóvel (só informativo)
    "client_name": socket.gethostname(),
    "dnd": False,           # não incomodar: guarda no histórico mas não mostra alertas
}


class Config:
    def __init__(self, path: Path | None = None) -> None:
        self.path = path or data_dir() / "config.json"
        self._lock = threading.Lock()
        self._data = dict(DEFAULTS)
        try:
            self._data.update(json.loads(self.path.read_text(encoding="utf-8")))
        except FileNotFoundError:
            pass
        except Exception:
            logging.getLogger(__name__).exception("config.json inválido; a usar valores por omissão")

    def get(self, key: str) -> Any:
        with self._lock:
            return self._data.get(key, DEFAULTS.get(key))

    def set(self, **values: Any) -> None:
        with self._lock:
            self._data.update(values)
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self._data, indent=2, ensure_ascii=False), encoding="utf-8")
            tmp.replace(self.path)

    @property
    def paired(self) -> bool:
        return bool(self.get("token"))
