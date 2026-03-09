#!/usr/bin/env python3
"""Simple X11 test card window for validating virtual-monitor capture regions."""

from __future__ import annotations

import argparse
import itertools
import tkinter as tk


def main() -> int:
    ap = argparse.ArgumentParser(description="Show animated test card on X11")
    ap.add_argument("--x", type=int, default=1920)
    ap.add_argument("--y", type=int, default=0)
    ap.add_argument("--w", type=int, default=1200)
    ap.add_argument("--h", type=int, default=2000)
    ap.add_argument("--title", default="WBeam X11 Testcard")
    args = ap.parse_args()

    root = tk.Tk()
    root.title(args.title)
    root.geometry(f"{args.w}x{args.h}+{args.x}+{args.y}")
    root.configure(bg="black")

    canvas = tk.Canvas(root, highlightthickness=0, bg="black")
    canvas.pack(fill=tk.BOTH, expand=True)

    colors = itertools.cycle(
        [
            ("#1e3a8a", "#bfdbfe"),
            ("#14532d", "#bbf7d0"),
            ("#7f1d1d", "#fecaca"),
            ("#5b21b6", "#ddd6fe"),
        ]
    )

    state = {"tick": 0}

    def draw() -> None:
        fg, text = next(colors)
        canvas.delete("all")
        w = max(canvas.winfo_width(), 100)
        h = max(canvas.winfo_height(), 100)
        # 4-quadrant pattern + timestamp-like counter.
        canvas.create_rectangle(0, 0, w // 2, h // 2, fill=fg, width=0)
        canvas.create_rectangle(w // 2, 0, w, h // 2, fill="black", width=0)
        canvas.create_rectangle(0, h // 2, w // 2, h, fill="white", width=0)
        canvas.create_rectangle(w // 2, h // 2, w, h, fill=text, width=0)
        state["tick"] += 1
        canvas.create_text(
            w // 2,
            h // 2,
            text=f"WBeam testcard tick={state['tick']}",
            fill="#111827",
            font=("DejaVu Sans", 32, "bold"),
        )
        root.after(700, draw)

    root.after(50, draw)
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

