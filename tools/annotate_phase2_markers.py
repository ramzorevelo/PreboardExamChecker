"""
annotate_phase2_markers.py — manual ground-truth annotation for Phase 2 markers.

For each image in capture6/ you click on the center of each of the 4 markers
in order: TM (top-left), TR (top-right), BM (bottom-left), BR (bottom-right).
A colored dot is drawn at each click.  Progress is saved after every image so
you can quit and resume at any time.

Controls
--------
  Left-click  : place dot for the current corner (auto-advances)
  U           : undo last dot
  S / Space   : skip this image
  R           : reset all 4 dots for current image
  Q / Esc     : quit and save progress

Output
------
  capture6/annotated/<filename>  : image with colored dots
  capture6/annotated/annotations.json : all positions as pixel coords + image size

Usage
-----
  python tools/annotate_phase2_markers.py
  python tools/annotate_phase2_markers.py --rerun   # re-annotate already-done images
  python tools/annotate_phase2_markers.py --stats   # print stats from saved JSON
"""

import argparse
import json
import os
import sys
import tkinter as tk
from tkinter import ttk

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageTk

# ── Paths ──────────────────────────────────────────────────────────────────────
SCRIPT_DIR  = os.path.dirname(os.path.abspath(__file__))
CAPTURE_DIR = os.path.normpath(os.path.join(SCRIPT_DIR, '..', 'capture6'))
ANNOT_DIR   = os.path.join(CAPTURE_DIR, 'annotated')
ANNOT_JSON  = os.path.join(ANNOT_DIR, 'annotations.json')

# ── Corner config ──────────────────────────────────────────────────────────────
CORNER_NAMES  = ['TM', 'TR', 'BM', 'BR']
# (R, G, B) for Pillow drawing and tkinter labels
CORNER_RGB = {
    'TM': (255,  60,  60),
    'TR': ( 60, 200,  60),
    'BM': ( 60, 120, 255),
    'BR': (220, 180,   0),
}
DOT_RADIUS = 7
CROSS_LEN  = 14
STATS_SIGMA = 2.5

# Display window: images are scaled to fit within this
MAX_W, MAX_H = 1100, 780


# ── Drawing on PIL images ──────────────────────────────────────────────────────

def draw_dot_pil(draw, cx, cy, corner, scale=1.0):
    r = int(DOT_RADIUS * scale)
    c_len = int(CROSS_LEN * scale)
    rgb = CORNER_RGB[corner]
    draw.ellipse((cx-r, cy-r, cx+r, cy+r), fill=rgb, outline=(255,255,255), width=1)
    draw.line([(cx-c_len, cy), (cx+c_len, cy)], fill=rgb, width=1)
    draw.line([(cx, cy-c_len), (cx, cy+c_len)], fill=rgb, width=1)
    draw.text((cx+r+3, cy-r), corner, fill=rgb)


def build_display(base_img, placed, next_corner, scale):
    """PIL image ready to show: base scaled + dots + status bar."""
    dw = max(1, int(base_img.width  * scale))
    dh = max(1, int(base_img.height * scale))
    disp = base_img.resize((dw, dh), Image.LANCZOS)
    draw = ImageDraw.Draw(disp)

    for corner, (cx, cy) in placed.items():
        draw_dot_pil(draw, int(cx * scale), int(cy * scale), corner, scale)

    if next_corner:
        rgb = CORNER_RGB[next_corner]
        status = f"Click: {next_corner}   |   U=undo  S=skip  R=reset  Q=quit"
    else:
        rgb = (200, 200, 200)
        status = "All 4 placed — press any key or wait to advance"

    bar_h = int(22 * max(scale, 1.0))
    bar = Image.new('RGB', (dw, bar_h), (30, 30, 30))
    bar_draw = ImageDraw.Draw(bar)
    bar_draw.text((6, 4), status, fill=rgb)
    combined = Image.new('RGB', (dw, dh + bar_h))
    combined.paste(bar, (0, 0))
    combined.paste(disp, (0, bar_h))
    return combined


# ── Stats ──────────────────────────────────────────────────────────────────────

def print_stats(annots):
    per = {c: {'cx': [], 'cy': []} for c in CORNER_NAMES}
    for fname, corners in annots.items():
        meta = corners.get('_meta', {})
        imgW = meta.get('imgW', 1)
        imgH = meta.get('imgH', 1)
        for corner in CORNER_NAMES:
            if corner in corners:
                cx_px, cy_px = corners[corner]
                per[corner]['cx'].append(cx_px / imgW)
                per[corner]['cy'].append(cy_px / imgH)

    W = 76
    print()
    print('=' * W)
    print('GROUND-TRUTH STATISTICS  (fractions of image dimensions)')
    print('=' * W)
    print(f"{'Corner':<7} {'N':>4}  {'cx mean':>8} {'+-std':>7}  {'cy mean':>8} {'+-std':>7}")
    print('-' * W)
    for corner in CORNER_NAMES:
        cx = np.array(per[corner]['cx'])
        cy = np.array(per[corner]['cy'])
        n  = len(cx)
        if n == 0:
            print(f"{corner:<7} {0:>4}  (no annotations)")
            continue
        print(f"{corner:<7} {n:>4}  {cx.mean():>8.4f} {cx.std():>7.4f}  "
              f"{cy.mean():>8.4f} {cy.std():>7.4f}")

    print()
    print('=' * W)
    print(f'RECOMMENDED ROI CONSTANTS  (mean +/- {STATS_SIGMA} sigma, clamped [0,1])')
    print('=' * W)
    for corner in CORNER_NAMES:
        cx = np.array(per[corner]['cx'])
        cy = np.array(per[corner]['cy'])
        n  = len(cx)
        if n == 0:
            print(f"  {corner}: no data")
            continue
        cx_lo = float(np.clip(cx.mean() - STATS_SIGMA * cx.std(), 0.0, 1.0))
        cx_hi = float(np.clip(cx.mean() + STATS_SIGMA * cx.std(), 0.0, 1.0))
        cy_lo = float(np.clip(cy.mean() - STATS_SIGMA * cy.std(), 0.0, 1.0))
        cy_hi = float(np.clip(cy.mean() + STATS_SIGMA * cy.std(), 0.0, 1.0))
        print(f"  {corner}:  cx=[{cx_lo:.4f}, {cx_hi:.4f}]  "
              f"cy=[{cy_lo:.4f}, {cy_hi:.4f}]  (n={n})")
    print()


# ── Tkinter annotator ──────────────────────────────────────────────────────────

class Annotator:
    def __init__(self, root, img_paths, annots, rerun):
        self.root     = root
        self.annots   = annots
        self.rerun    = rerun

        already_done = set(annots.keys())
        if rerun:
            self.todo = list(img_paths)
        else:
            self.todo = [p for p in img_paths
                         if os.path.basename(p) not in already_done]

        self.idx      = 0
        self.placed   = {}   # corner -> (cx_orig, cy_orig) in original image pixels
        self.scale    = 1.0
        self.base_img = None  # PIL Image at original size
        self.quit     = False
        self.result   = 'running'  # 'running' | 'done' | 'quit'

        root.title('Phase 2 marker annotation')
        root.configure(bg='#1e1e1e')
        root.bind('<KeyPress>', self._on_key)

        # Progress label
        self.prog_var = tk.StringVar()
        tk.Label(root, textvariable=self.prog_var, bg='#1e1e1e', fg='#aaa',
                 font=('Consolas', 10)).pack(side=tk.TOP, anchor='w', padx=6, pady=2)

        self.canvas = tk.Canvas(root, bg='#111', highlightthickness=0)
        self.canvas.pack(fill=tk.BOTH, expand=True)
        self.canvas.bind('<Button-1>', self._on_click)

        # Force geometry calculation so winfo_width/height return real values
        root.update_idletasks()
        self._load_current()

    # ── Image loading ──────────────────────────────────────────────────────────

    def _load_current(self):
        if self.idx >= len(self.todo):
            self.result = 'done'
            self.root.quit()
            return

        path = self.todo[self.idx]
        self.placed = {}
        self.base_img = Image.open(path).convert('RGB')
        total = len(self.todo)
        done  = len(self.annots)
        fname = os.path.basename(path)
        self.prog_var.set(
            f"[{self.idx+1}/{total}]  {fname}  "
            f"({done} annotated so far)  — U undo  S skip  R reset  Q quit"
        )
        self._refresh()

    def _refresh(self):
        if self.base_img is None:
            return
        iw, ih = self.base_img.size
        cw = self.canvas.winfo_width()
        ch = self.canvas.winfo_height()
        if cw < 50: cw = MAX_W
        if ch < 50: ch = MAX_H
        self.scale = min(cw / iw, ch / ih, 1.0)
        self.scale = max(self.scale, 0.01)

        next_corner = next((c for c in CORNER_NAMES if c not in self.placed), None)
        pil_disp = build_display(self.base_img, self.placed, next_corner, self.scale)
        self._tkimg = ImageTk.PhotoImage(pil_disp)
        self.canvas.delete('all')
        self.canvas.create_image(0, 0, anchor='nw', image=self._tkimg)

        # Highlight next corner name in title bar color
        if next_corner:
            r, g, b = CORNER_RGB[next_corner]
            hex_color = f'#{r:02x}{g:02x}{b:02x}'
            self.root.title(f'annotate — click {next_corner}')
        else:
            self.root.title('annotate — all 4 placed')

    # ── Event handlers ─────────────────────────────────────────────────────────

    def _on_click(self, event):
        next_corner = next((c for c in CORNER_NAMES if c not in self.placed), None)
        if next_corner is None:
            return
        # Convert display coords back to original image pixels
        cx_orig = int(event.x / self.scale)
        cy_orig = int(event.y / self.scale)
        # Subtract the status bar height (22px * scale)
        bar_h = int(22 * max(self.scale, 1.0))
        cy_orig = int((event.y - bar_h) / self.scale)
        if cy_orig < 0:
            return  # clicked on status bar
        self.placed[next_corner] = (cx_orig, cy_orig)
        self._refresh()

        all_placed = all(c in self.placed for c in CORNER_NAMES)
        if all_placed:
            self.root.after(500, self._save_and_advance)

    def _on_key(self, event):
        k = event.keysym.lower()
        if k in ('q', 'escape'):
            self.result = 'quit'
            self.root.quit()
        elif k in ('s', 'n', 'space'):
            self._advance(save=False)
        elif k == 'r':
            self.placed.clear()
            self._refresh()
        elif k == 'u':
            if self.placed:
                last = [c for c in CORNER_NAMES if c in self.placed][-1]
                del self.placed[last]
                self._refresh()
        elif k in ('return', 'enter'):
            if all(c in self.placed for c in CORNER_NAMES):
                self._save_and_advance()

    # ── Save and navigation ────────────────────────────────────────────────────

    def _save_and_advance(self):
        path  = self.todo[self.idx]
        fname = os.path.basename(path)
        iw, ih = self.base_img.size

        # Save annotated image (dots drawn at original resolution)
        annot_img = self.base_img.copy()
        draw = ImageDraw.Draw(annot_img)
        for corner, (cx, cy) in self.placed.items():
            draw_dot_pil(draw, cx, cy, corner, scale=1.0)
        os.makedirs(ANNOT_DIR, exist_ok=True)
        annot_img.save(os.path.join(ANNOT_DIR, fname))

        # Update JSON
        entry = {'_meta': {'imgW': iw, 'imgH': ih}}
        for corner, (cx, cy) in self.placed.items():
            entry[corner] = [cx, cy]
        self.annots[fname] = entry
        with open(ANNOT_JSON, 'w') as f:
            json.dump(self.annots, f, indent=2)

        corners_placed = [c for c in CORNER_NAMES if c in self.placed]
        print(f"  [{self.idx+1}/{len(self.todo)}] {fname}  saved ({', '.join(corners_placed)})")

        self._advance(save=True)

    def _advance(self, save):
        if not save:
            fname = os.path.basename(self.todo[self.idx])
            print(f"  [{self.idx+1}/{len(self.todo)}] {fname}  skipped")
        self.idx += 1
        self._load_current()


# ── Entry point ────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--rerun', action='store_true',
                        help='re-annotate already-done images')
    parser.add_argument('--stats', action='store_true',
                        help='print stats from existing JSON and exit')
    args = parser.parse_args()

    os.makedirs(ANNOT_DIR, exist_ok=True)

    annots = {}
    if os.path.exists(ANNOT_JSON):
        with open(ANNOT_JSON) as f:
            annots = json.load(f)

    if args.stats:
        if not annots:
            sys.exit("No annotations found — run without --stats first.")
        print_stats(annots)
        return

    exts = ('.jpg', '.jpeg', '.png')
    img_paths = sorted(
        os.path.join(CAPTURE_DIR, fn)
        for fn in os.listdir(CAPTURE_DIR)
        if fn.lower().endswith(exts) and os.path.isfile(os.path.join(CAPTURE_DIR, fn))
    )

    already = set(annots.keys())
    todo = img_paths if args.rerun else [p for p in img_paths
                                         if os.path.basename(p) not in already]
    if not todo:
        print("All images already annotated. Use --rerun to redo, --stats to view results.")
        print_stats(annots)
        return

    print(f"{len(todo)} images to annotate  ({len(already)} already done, {len(img_paths)} total)")
    print("Left-click = place dot   U=undo   S=skip   R=reset   Q=quit")
    print()

    root = tk.Tk()
    root.geometry(f"{MAX_W}x{MAX_H+40}")
    app  = Annotator(root, img_paths, annots, args.rerun)
    root.mainloop()

    if annots:
        print_stats(annots)


if __name__ == '__main__':
    main()
