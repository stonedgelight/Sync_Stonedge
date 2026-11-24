"""Janelas Tkinter: emparelhamento, histórico e pop-up de recurso.

O Tkinter só pode ser usado na thread principal; as outras threads (asyncio, ícone da
bandeja) pedem trabalho através de UI.call(), que coloca a função numa fila.
"""
from __future__ import annotations

import logging
import queue
import time
import tkinter as tk
from datetime import datetime
from tkinter import messagebox, ttk
from typing import Callable

from . import APP_NAME, __version__
from .history import History

log = logging.getLogger(__name__)

BRAND = "#0E2A33"
ACCENT = "#38D9A9"
MUTED = "#5A6B70"


class UI:
    def __init__(self, history: History) -> None:
        self.history = history
        self.root = tk.Tk()
        self.root.withdraw()
        self.root.title(APP_NAME)
        self._q: queue.Queue[Callable[[], None]] = queue.Queue()
        self._history_win: HistoryWindow | None = None
        self._toasts: list[tk.Toplevel] = []
        self.root.after(100, self._pump)
        history.listeners.append(lambda: self.call(self._refresh_history))

    # ---- infraestrutura -------------------------------------------------------

    def call(self, fn: Callable[[], None]) -> None:
        """Agenda fn na thread do Tkinter (seguro a partir de qualquer thread)."""
        self._q.put(fn)

    def _pump(self) -> None:
        try:
            while True:
                fn = self._q.get_nowait()
                try:
                    fn()
                except Exception:
                    log.exception("Erro na UI")
        except queue.Empty:
            pass
        self.root.after(100, self._pump)

    def mainloop(self) -> None:
        self.root.mainloop()

    def quit(self) -> None:
        self.call(self.root.quit)

    # ---- emparelhamento ---------------------------------------------------------

    def ask_pin(self, host: str, fingerprint: str, hint: str, done: Callable[[str | None], None]) -> None:
        self.call(lambda: PinDialog(self.root, host, fingerprint, hint, done))

    # ---- histórico ---------------------------------------------------------------

    def show_history(self) -> None:
        def _open() -> None:
            if self._history_win is None or not self._history_win.alive:
                self._history_win = HistoryWindow(self.root, self.history)
            else:
                self._history_win.focus()
        self.call(_open)

    def _refresh_history(self) -> None:
        if self._history_win is not None and self._history_win.alive:
            self._history_win.reload()

    def info(self, title: str, text: str) -> None:
        self.call(lambda: messagebox.showinfo(title, text))

    # ---- pop-up de recurso (quando não há alertas nativos) ----------------------

    def toast(self, title: str, text: str, icon_path: str | None = None) -> None:
        self.call(lambda: self._toast(title, text))

    def _toast(self, title: str, text: str) -> None:
        w = tk.Toplevel(self.root)
        w.overrideredirect(True)
        w.attributes("-topmost", True)
        w.configure(bg=BRAND)
        frame = tk.Frame(w, bg=BRAND, padx=14, pady=10)
        frame.pack(fill="both", expand=True)
        tk.Label(frame, text=title, bg=BRAND, fg=ACCENT, font=("Segoe UI", 10, "bold"),
                 anchor="w", justify="left", wraplength=320).pack(fill="x")
        tk.Label(frame, text=text, bg=BRAND, fg="white", font=("Segoe UI", 10),
                 anchor="w", justify="left", wraplength=320).pack(fill="x", pady=(4, 0))
        w.update_idletasks()
        width, height = 360, w.winfo_reqheight()
        sw, sh = w.winfo_screenwidth(), w.winfo_screenheight()
        offset = sum(t.winfo_height() + 8 for t in self._toasts if t.winfo_exists())
        w.geometry(f"{width}x{height}+{sw - width - 16}+{sh - height - 64 - offset}")
        self._toasts.append(w)

        def close(_e=None) -> None:
            if w.winfo_exists():
                w.destroy()
            self._toasts[:] = [t for t in self._toasts if t.winfo_exists()]

        def click(_e=None) -> None:
            close()
            self.show_history()

        for widget in (w, frame, *frame.winfo_children()):
            widget.bind("<Button-1>", click)
        w.after(7000, close)


class PinDialog:
    def __init__(self, root: tk.Tk, host: str, fingerprint: str, hint: str,
                 done: Callable[[str | None], None]) -> None:
        self.done = done
        self.answered = False
        w = self.w = tk.Toplevel(root)
        w.title(f"{APP_NAME} · Emparelhar")
        w.attributes("-topmost", True)
        w.resizable(False, False)
        body = ttk.Frame(w, padding=18)
        body.pack(fill="both", expand=True)

        ttk.Label(body, text="Emparelhar com o telemóvel", font=("Segoe UI", 13, "bold")).pack(anchor="w")
        ttk.Label(
            body,
            text=(f"Telemóvel encontrado em {host}.\n\n"
                  "No telemóvel, abre o Sync_Stonedge, toca em «Gerar código de emparelhamento» "
                  "e escreve aqui o código de 6 dígitos."),
            wraplength=380, justify="left",
        ).pack(anchor="w", pady=(8, 8))

        fp = " ".join(fingerprint[i:i + 4] for i in range(0, 16, 4))
        ttk.Label(body, text=f"Certificado: {fp}  (deve ser igual ao que o telemóvel mostra)",
                  foreground=MUTED, wraplength=380).pack(anchor="w")

        if hint:
            ttk.Label(body, text=hint, foreground="#B3261E", wraplength=380).pack(anchor="w", pady=(8, 0))

        self.var = tk.StringVar()
        entry = ttk.Entry(body, textvariable=self.var, font=("Consolas", 22), width=8, justify="center")
        entry.pack(pady=12)
        entry.focus_set()

        buttons = ttk.Frame(body)
        buttons.pack(fill="x")
        ttk.Button(buttons, text="Cancelar", command=self.cancel).pack(side="right")
        ttk.Button(buttons, text="Emparelhar", command=self.ok).pack(side="right", padx=8)
        w.bind("<Return>", lambda _e: self.ok())
        w.bind("<Escape>", lambda _e: self.cancel())
        w.protocol("WM_DELETE_WINDOW", self.cancel)
        w.lift()
        w.after(200, lambda: w.focus_force())

    def ok(self) -> None:
        pin = "".join(c for c in self.var.get() if c.isdigit())
        if len(pin) != 6:
            messagebox.showwarning(APP_NAME, "O código tem 6 dígitos.", parent=self.w)
            return
        self._finish(pin)

    def cancel(self) -> None:
        self._finish(None)

    def _finish(self, value: str | None) -> None:
        if not self.answered:
            self.answered = True
            self.done(value)
        self.w.destroy()


class HistoryWindow:
    def __init__(self, root: tk.Tk, history: History) -> None:
        self.history = history
        self.alive = True
        w = self.w = tk.Toplevel(root)
        w.title(f"{APP_NAME} {__version__} · Histórico")
        w.geometry("900x520")
        w.protocol("WM_DELETE_WINDOW", self.close)

        top = ttk.Frame(w, padding=(10, 8))
        top.pack(fill="x")
        ttk.Label(top, text="Procurar:").pack(side="left")
        self.query = tk.StringVar()
        self.query.trace_add("write", lambda *_: self.reload())
        ttk.Entry(top, textvariable=self.query, width=40).pack(side="left", padx=6)
        ttk.Button(top, text="Limpar histórico", command=self.clear).pack(side="right")

        cols = ("hora", "app", "titulo", "texto")
        self.tree = ttk.Treeview(w, columns=cols, show="headings")
        for c, label, width in (("hora", "Hora", 120), ("app", "App", 140),
                                ("titulo", "Título", 200), ("texto", "Texto", 420)):
            self.tree.heading(c, text=label)
            self.tree.column(c, width=width, anchor="w")
        scroll = ttk.Scrollbar(w, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=scroll.set)
        self.tree.pack(side="left", fill="both", expand=True, padx=(10, 0), pady=(0, 10))
        scroll.pack(side="left", fill="y", pady=(0, 10), padx=(0, 10))
        self.tree.bind("<Double-1>", self.details)
        self.tree.tag_configure("removed", foreground="#8A9599")
        self._rows: dict[str, dict] = {}
        self.reload()

    def reload(self) -> None:
        q = self.query.get().strip().lower()
        self.tree.delete(*self.tree.get_children())
        self._rows.clear()
        for i, it in enumerate(reversed(self.history.items())):
            hay = f"{it.get('app', '')} {it.get('title', '')} {it.get('text', '')}".lower()
            if q and q not in hay:
                continue
            ts = datetime.fromtimestamp(it.get("time", time.time() * 1000) / 1000).strftime("%d/%m %H:%M:%S")
            iid = str(i)
            self._rows[iid] = it
            self.tree.insert("", "end", iid=iid, values=(
                ts, it.get("app", ""), it.get("title", ""), (it.get("text", "") or "").replace("\n", " ")
            ), tags=("removed",) if it.get("removed") else ())

    def details(self, _e=None) -> None:
        sel = self.tree.selection()
        if not sel:
            return
        it = self._rows.get(sel[0], {})
        messagebox.showinfo(
            f"{it.get('app', '')}",
            f"{it.get('title', '')}\n\n{it.get('text', '')}" + (f"\n\n{it['sub']}" if it.get("sub") else ""),
            parent=self.w,
        )

    def clear(self) -> None:
        if messagebox.askyesno(APP_NAME, "Apagar todo o histórico deste PC?", parent=self.w):
            self.history.clear()

    def focus(self) -> None:
        self.w.deiconify()
        self.w.lift()
        self.w.focus_force()

    def close(self) -> None:
        self.alive = False
        self.w.destroy()
