"""
annotate_phase1_markers.py — manual ground-truth annotation for PHASE 1 markers.

Phase-1 copy of annotate_phase2_markers.py (that script is left untouched). The
info-zone has 4 markers; click their centers IN THIS ORDER:

    TL (info-zone top-left)      TM (info-zone top-middle)
    BL (info-zone bottom-left)   BM (info-zone bottom-middle)

These are the 4 black squares around the STUDENT ID / TEST-SET / IMPORTANT block
on the LEFT portion of the sheet — NOT the answer-grid markers on the right. The
order matches MarkerDetector's image-quadrant order (TL=top-left quadrant,
TM=top-right quadrant, BL=bottom-left, BM=bottom-right), so the printed ROI
fractions drop straight into a Phase-1 MARKER_ROI_FRAC = [TL, TM, BL, BM].

Input  : raw `info_captured_*.png` frames (InfoZoneProcessor now saves these).
         Drop them in CAPTURE_DIR; overlay files (info_captured_markers_*,
         info_box_*, *_overlay) are auto-excluded so you can dump the whole
         debug folder in and only the raws get annotated.

Output : <CAPTURE_DIR>/annotated_phase1/<filename>   (dots drawn)
         <CAPTURE_DIR>/annotated_phase1/annotations.json
Progress is saved after every image, so you can quit and resume anytime.

Controls : Left-click place (auto-advances) · U undo · S/Space skip · R reset · Q/Esc quit
Usage    : python annotate_phase1_markers.py
           python annotate_phase1_markers.py --stats    # print ROI fractions + exit
           python annotate_phase1_markers.py --rerun    # re-annotate done images
"""

import argparse
import json
import os
import sys
import tkinter as tk

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageTk

# ── Paths ──────────────────────────────────────────────────────────────────────
# Folder holding the raw Phase-1 captures. Default: the repo infoCap\ folder the
# user already copies debug images into (raws are auto-selected, overlays skipped).
CAPTURE_DIR = os.environ.get(
    'PHASE1_CAP_DIR',
    r'C:\Users\jessa\StudioProjects\preboard\infoCap'
)
ANNOT_DIR  = os.path.join(CAPTURE_DIR, 'annotated_phase1')
ANNOT_JSON = os.path.join(ANNOT_DIR, 'annotations.json')

# Only annotate the raw captures; skip every overlay the pipeline also saves.
INCLUDE_PREFIX = 'info_captured'      # raw frames are info_captured_<ts>.png
EXCLUDE_SUBSTRS = ('markers', 'overlay', 'box_corners', 'warped', 'binary', 'dynamic')

# ── Corner config ──────────────────────────────────────────────────────────────
CORNER_NAMES = ['TL', 'TM', 'BL', 'BM']
# (R, G, B) — match InfoZoneProcessor.saveInfoCapturedMarkers for visual parity
CORNER_RGB = {
    'TL': ( 60, 220,  60),   # green
    'TM': (255,  60,  60),   # red
    'BL': (240, 220,   0),   # yellow
    'BM': (220,  60, 220),   # magenta
}
DOT_RADIUS = 7
CROSS_LEN  = 14
STATS_SIGMA = 2.5

MAX_W, MAX_H = 1100, 780


# ── Drawing on PIL images ──────────────────────────────────────────────────────

def draw_dot_pil(draw, cx, cy, corner, scale=1.0):
    r = int(DOT_RADIUS * scale)
    c_len = int(CROSS_LEN * scale)
    rgb = CORNER_RGB[corner]
    draw.ellipse((cx-r, cy-r, cx+r, cy+r), fill=rgb, outline=(255, 255, 255), width=1)
    draw.line([(cx-c_len, cy), (cx+c_len, cy)], fill=rgb, width=1)
    draw.line([(cx, cy-c_len), (cx, cy+c_len)], fill=rgb, width=1)
    draw.text((cx+r+3, cy-r), corner, fill=rgb)


def build_display(base_img, placed, next_corner, scale):
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
    ImageDraw.Draw(bar).text((6, 4), status, fill=rgb)
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
    print('PHASE 1 GROUND-TRUTH STATISTICS  (fractions of image dimensions)')
    print('=' * W)
    print(f"{'Corner':<7} {'N':>4}  {'cx mean':>8} {'+-std':>7}  {'cy mean':>8} {'+-std':>7}")
    print('-' * W)
    for corner in CORNER_NAMES:
        cx = np.array(per[corner]['cx']); cy = np.array(per[corner]['cy']); n = len(cx)
        if n == 0:
            print(f"{corner:<7} {0:>4}  (no annotations)")
            continue
        print(f"{corner:<7} {n:>4}  {cx.mean():>8.4f} {cx.std():>7.4f}  "
              f"{cy.mean():>8.4f} {cy.std():>7.4f}")

    print()
    print('=' * W)
    print(f'PHASE-1 MARKER_ROI_FRAC  (mean +/- {STATS_SIGMA} sigma, clamped [0,1])')
    print('order [TL, TM, BL, BM] = [cx_lo, cx_hi, cy_lo, cy_hi]')
    print('=' * W)
    for corner in CORNER_NAMES:
        cx = np.array(per[corner]['cx']); cy = np.array(per[corner]['cy']); n = len(cx)
        if n == 0:
            print(f"  {corner}: no data"); continue
        cx_lo = float(np.clip(cx.mean() - STATS_SIGMA * cx.std(), 0.0, 1.0))
        cx_hi = float(np.clip(cx.mean() + STATS_SIGMA * cx.std(), 0.0, 1.0))
        cy_lo = float(np.clip(cy.mean() - STATS_SIGMA * cy.std(), 0.0, 1.0))
        cy_hi = float(np.clip(cy.mean() + STATS_SIGMA * cy.std(), 0.0, 1.0))
        print(f"  floatArrayOf({cx_lo:.3f}f, {cx_hi:.3f}f, {cy_lo:.3f}f, {cy_hi:.3f}f),"
              f"  // {corner} (n={n})")
    print()


# ── Tkinter annotator ──────────────────────────────────────────────────────────

class Annotator:
    def __init__(self, root, img_paths, annots, rerun):
        self.root   = root
        self.annots = annots
        self.rerun  = rerun

        already_done = set(annots.keys())
        self.todo = list(img_paths) if rerun else \
            [p for p in img_paths if os.path.basename(p) not in already_done]

        self.idx      = 0
        self.placed   = {}
        self.scale    = 1.0
        self.base_img = None
        self.result   = 'running'

        root.title('Phase 1 marker annotation')
        root.configure(bg='#1e1e1e')
        root.bind('<KeyPress>', self._on_key)

        self.prog_var = tk.StringVar()
        tk.Label(root, textvariable=self.prog_var, bg='#1e1e1e', fg='#aaa',
                 font=('Consolas', 10)).pack(side=tk.TOP, anchor='w', padx=6, pady=2)

        self.canvas = tk.Canvas(root, bg='#111', highlightthickness=0)
        self.canvas.pack(fill=tk.BOTH, expand=True)
        self.canvas.bind('<Button-1>', self._on_click)

        root.update_idletasks()
        self._load_current()

    def _load_current(self):
        if self.idx >= len(self.todo):
            self.result = 'done'; self.root.quit(); return
        path = self.todo[self.idx]
        self.placed = {}
        self.base_img = Image.open(path).convert('RGB')
        self.prog_var.set(
            f"[{self.idx+1}/{len(self.todo)}]  {os.path.basename(path)}  "
            f"({len(self.annots)} annotated so far)  — U undo  S skip  R reset  Q quit"
        )
        self._refresh()

    def _refresh(self):
        if self.base_img is None: return
        iw, ih = self.base_img.size
        cw = self.canvas.winfo_width() or MAX_W
        ch = self.canvas.winfo_height() or MAX_H
        if cw < 50: cw = MAX_W
        if ch < 50: ch = MAX_H
        self.scale = max(min(cw / iw, ch / ih, 1.0), 0.01)
        next_corner = next((c for c in CORNER_NAMES if c not in self.placed), None)
        pil_disp = build_display(self.base_img, self.placed, next_corner, self.scale)
        self._tkimg = ImageTk.PhotoImage(pil_disp)
        self.canvas.delete('all')
        self.canvas.create_image(0, 0, anchor='nw', image=self._tkimg)
        self.root.title(f'annotate — click {next_corner}' if next_corner
                        else 'annotate — all 4 placed')

    def _on_click(self, event):
        next_corner = next((c for c in CORNER_NAMES if c not in self.placed), None)
        if next_corner is None: return
        bar_h = int(22 * max(self.scale, 1.0))
        cy_orig = int((event.y - bar_h) / self.scale)
        if cy_orig < 0: return
        cx_orig = int(event.x / self.scale)
        self.placed[next_corner] = (cx_orig, cy_orig)
        self._refresh()
        if all(c in self.placed for c in CORNER_NAMES):
            self.root.after(500, self._save_and_advance)

    def _on_key(self, event):
        k = event.keysym.lower()
        if k in ('q', 'escape'):
            self.result = 'quit'; self.root.quit()
        elif k in ('s', 'n', 'space'):
            self._advance(save=False)
        elif k == 'r':
            self.placed.clear(); self._refresh()
        elif k == 'u':
            if self.placed:
                last = [c for c in CORNER_NAMES if c in self.placed][-1]
                del self.placed[last]; self._refresh()
        elif k in ('return', 'enter'):
            if all(c in self.placed for c in CORNER_NAMES):
                self._save_and_advance()

    def _save_and_advance(self):
        path  = self.todo[self.idx]
        fname = os.path.basename(path)
        iw, ih = self.base_img.size
        annot_img = self.base_img.copy()
        draw = ImageDraw.Draw(annot_img)
        for corner, (cx, cy) in self.placed.items():
            draw_dot_pil(draw, cx, cy, corner, scale=1.0)
        os.makedirs(ANNOT_DIR, exist_ok=True)
        annot_img.save(os.path.join(ANNOT_DIR, fname))

        entry = {'_meta': {'imgW': iw, 'imgH': ih}}
        for corner, (cx, cy) in self.placed.items():
            entry[corner] = [cx, cy]
        self.annots[fname] = entry
        with open(ANNOT_JSON, 'w') as f:
            json.dump(self.annots, f, indent=2)
        print(f"  [{self.idx+1}/{len(self.todo)}] {fname}  saved "
              f"({', '.join(c for c in CORNER_NAMES if c in self.placed)})")
        self._advance(save=True)

    def _advance(self, save):
        if not save:
            print(f"  [{self.idx+1}/{len(self.todo)}] "
                  f"{os.path.basename(self.todo[self.idx])}  skipped")
        self.idx += 1
        self._load_current()


# ── Entry point ────────────────────────────────────────────────────────────────

def _list_raw_captures():
    exts = ('.jpg', '.jpeg', '.png')
    out = []
    if not os.path.isdir(CAPTURE_DIR):
        return out
    for fn in sorted(os.listdir(CAPTURE_DIR)):
        low = fn.lower()
        if not low.endswith(exts): continue
        if not low.startswith(INCLUDE_PREFIX): continue
        if any(s in low for s in EXCLUDE_SUBSTRS): continue
        full = os.path.join(CAPTURE_DIR, fn)
        if os.path.isfile(full): out.append(full)
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--rerun', action='store_true', help='re-annotate done images')
    parser.add_argument('--stats', action='store_true', help='print ROI stats and exit')
    args = parser.parse_args()

    os.makedirs(ANNOT_DIR, exist_ok=True)
    annots = {}
    if os.path.exists(ANNOT_JSON):
        with open(ANNOT_JSON) as f:
            annots = json.load(f)

    if args.stats:
        if not annots:
            sys.exit("No annotations found — run without --stats first.")
        print_stats(annots); return

    img_paths = _list_raw_captures()
    if not img_paths:
        sys.exit(f"No raw 'info_captured_*' frames in {CAPTURE_DIR}. "
                 f"Set PHASE1_CAP_DIR or drop the raw captures there.")

    already = set(annots.keys())
    todo = img_paths if args.rerun else \
        [p for p in img_paths if os.path.basename(p) not in already]
    if not todo:
        print("All images already annotated. Use --rerun to redo, --stats to view results.")
        print_stats(annots); return

    print(f"{len(todo)} images to annotate  ({len(already)} done, {len(img_paths)} total)")
    print("Click order: TL, TM, BL, BM   (left-block markers, NOT the answer grid)")
    print("Left-click place · U undo · S skip · R reset · Q quit\n")

    root = tk.Tk()
    root.geometry(f"{MAX_W}x{MAX_H+40}")
    Annotator(root, img_paths, annots, args.rerun)
    root.mainloop()

    if annots:
        print_stats(annots)


if __name__ == '__main__':
    main()
