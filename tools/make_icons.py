"""Gera os ícones da app de PC (PNG e ICO) a partir do desenho em tray.py."""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "desktop"))
from sync_stonedge.tray import make_icon  # noqa: E402

out = Path(__file__).resolve().parent.parent / "desktop" / "assets"
out.mkdir(parents=True, exist_ok=True)
big = make_icon("connected", 256)
big.save(out / "icon.png")
big.save(out / "icon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print("ícones gerados em", out)
