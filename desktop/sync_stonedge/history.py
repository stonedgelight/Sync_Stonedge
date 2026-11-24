"""Histórico das notificações recebidas (os alertas desaparecem; o histórico fica)."""
from __future__ import annotations

import json
import logging
import threading
from collections import deque
from pathlib import Path
from typing import Callable

from .config import data_dir

log = logging.getLogger(__name__)
MAX_ITEMS = 300


class History:
    def __init__(self, path: Path | None = None) -> None:
        self.path = path or data_dir() / "history.json"
        self._lock = threading.Lock()
        self._items: deque[dict] = deque(maxlen=MAX_ITEMS)
        self.listeners: list[Callable[[], None]] = []
        try:
            for it in json.loads(self.path.read_text(encoding="utf-8")):
                self._items.append(it)
        except FileNotFoundError:
            pass
        except Exception:
            log.exception("history.json inválido")

    def add(self, item: dict) -> bool:
        """Acrescenta; devolve False se já existia (mesmo id e conteúdo)."""
        with self._lock:
            for old in self._items:
                if old.get("id") == item.get("id") and old.get("text") == item.get("text") \
                        and old.get("title") == item.get("title"):
                    return False
            self._items.append(item)
            self._save()
        self._notify()
        return True

    def mark_removed(self, notif_id: str) -> None:
        with self._lock:
            for it in self._items:
                if it.get("id") == notif_id:
                    it["removed"] = True
            self._save()
        self._notify()

    def items(self) -> list[dict]:
        with self._lock:
            return list(self._items)

    def clear(self) -> None:
        with self._lock:
            self._items.clear()
            self._save()
        self._notify()

    def _save(self) -> None:
        try:
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(list(self._items), ensure_ascii=False), encoding="utf-8")
            tmp.replace(self.path)
        except Exception:
            log.exception("Não foi possível gravar o histórico")

    def _notify(self) -> None:
        for cb in list(self.listeners):
            try:
                cb()
            except Exception:
                log.exception("listener do histórico falhou")
