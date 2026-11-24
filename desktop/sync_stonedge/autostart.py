"""Arranque automático com a sessão (Windows: chave Run; Linux: ~/.config/autostart)."""
from __future__ import annotations

import os
import shlex
import sys
from pathlib import Path

from . import APP_NAME

_RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def _command() -> str:
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}"'
    exe = Path(sys.executable)
    if sys.platform == "win32":
        pyw = exe.with_name("pythonw.exe")
        exe = pyw if pyw.exists() else exe
        return f'"{exe}" -m sync_stonedge'
    return f"{shlex.quote(str(exe))} -m sync_stonedge"


def _desktop_file() -> Path:
    base = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config")
    return base / "autostart" / "sync_stonedge.desktop"


def is_enabled() -> bool:
    if sys.platform == "win32":
        import winreg

        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _RUN_KEY) as k:
                winreg.QueryValueEx(k, APP_NAME)
                return True
        except OSError:
            return False
    return _desktop_file().exists()


def set_enabled(enabled: bool) -> None:
    if sys.platform == "win32":
        import winreg

        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
            if enabled:
                winreg.SetValueEx(k, APP_NAME, 0, winreg.REG_SZ, _command())
            else:
                try:
                    winreg.DeleteValue(k, APP_NAME)
                except OSError:
                    pass
        return

    f = _desktop_file()
    if enabled:
        f.parent.mkdir(parents=True, exist_ok=True)
        cwd = Path(__file__).resolve().parent.parent
        f.write_text(
            "[Desktop Entry]\n"
            "Type=Application\n"
            f"Name={APP_NAME}\n"
            "Comment=Notificações do telemóvel no PC\n"
            f"Exec={_command()}\n"
            f"Path={cwd}\n"
            "X-GNOME-Autostart-enabled=true\n",
            encoding="utf-8",
        )
    elif f.exists():
        f.unlink()
