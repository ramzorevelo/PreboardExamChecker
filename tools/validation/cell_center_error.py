"""
cell_center_error.py — quantify per-cell alignment error (plan Part 3).

Operates in OVERLAY-PARSE mode on the `answer_warped_decisions_*.png` debug
images (the pipeline draws each detected answer cell as a thin green rectangle,
marked cells in red). For every detected cell box it measures the offset between
the box CENTER and the local dark-mass centroid of the printed bracket structure
(the annotation green/red is masked out first). The bracket frame is symmetric
about the true cell center, so a large offset means the detection box sits off
the real cell — exactly the col1/col5 bend failure we're fixing.

It aggregates |dx|,|dy| per column (1..5, split on box x) so the col1/col5
gradient is visible, plus a single per-image "alignment score" (mean |offset|)
usable as a before/after regression metric: run it on a bent-sheet capture's
overlay before and after enabling A1/A2 (ENABLE_CELL_LOCAL_SNAP) on-device and
confirm the score drops while flat-sheet overlays don't get worse.

CAVEAT: the green/red lines partially occlude the underlying bracket pixels, so
absolute offsets carry a few px of parse noise. The METRIC IS RELATIVE — compare
the same sheet before vs after, not absolute thresholds.

Usage:
    python cell_center_error.py <dir-or-glob> [<dir-or-glob> ...]
    (default: C:\\tmp\\misaligned and C:\\tmp\\blurry)

Outputs: printed per-image + per-column table, and C:\\tmp\\cal\\cell_center_error.csv
"""

import csv
import glob
import os
import sys

import cv2
import numpy as np

OUT_CSV = r"C:\tmp\cal\cell_center_error.csv"

# Detected answer cells are small boxes; reject the big column/subject frames and
# tiny speckle by box size (px on the ~600px-wide overlay).
MIN_BOX_W, MAX_BOX_W = 6, 45
MIN_BOX_H, MAX_BOX_H = 6, 45

SEARCH_PAD = 4  # px window grown around each box for the dark-mass centroid


def detect_cell_boxes(bgr):
    """Green (and red) thin rectangles → list of (x, y, w, h) in image coords."""
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    # Green annotation
    green = cv2.inRange(hsv, (40, 80, 80), (85, 255, 255))
    # Red annotation (two hue wraps)
    red = cv2.inRange(hsv, (0, 80, 80), (10, 255, 255)) | \
        cv2.inRange(hsv, (170, 80, 80), (180, 255, 255))
    ann = green | red
    # Close the thin rectangle outlines into filled blobs so boundingRect is the cell
    ann = cv2.morphologyEx(ann, cv2.MORPH_CLOSE,
                           cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3)))
    cnts, _ = cv2.findContours(ann, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    boxes = []
    for c in cnts:
        x, y, w, h = cv2.boundingRect(c)
        if MIN_BOX_W <= w <= MAX_BOX_W and MIN_BOX_H <= h <= MAX_BOX_H:
            boxes.append((x, y, w, h))
    return boxes, ann


def measure(path):
    bgr = cv2.imread(path)
    if bgr is None:
        return None
    H, W = bgr.shape[:2]
    boxes, ann = detect_cell_boxes(bgr)
    if not boxes:
        return None

    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    # Blank the annotation pixels so they don't bias the dark-mass centroid; set
    # them to a mid value (won't count as dark).
    gray[ann > 0] = 200
    dark = gray < 110  # printed bracket/letter ink

    # Column split: 5 equal x-bands across the answer area.
    def col_of(cx):
        return min(4, max(0, int(cx / W * 5)))

    rows = []  # (col, dx, dy)
    for (x, y, w, h) in boxes:
        cx, cy = x + w / 2.0, y + h / 2.0
        x0 = max(0, x - SEARCH_PAD)
        y0 = max(0, y - SEARCH_PAD)
        x1 = min(W, x + w + SEARCH_PAD)
        y1 = min(H, y + h + SEARCH_PAD)
        sub = dark[y0:y1, x0:x1]
        m = sub.sum()
        if m < 0.03 * sub.size:
            continue  # no reliable structure
        ys, xs = np.nonzero(sub)
        mcx = xs.mean() + x0
        mcy = ys.mean() + y0
        rows.append((col_of(cx), abs(mcx - cx), abs(mcy - cy)))
    return rows


def summarize(name, allrows):
    print(f"\n=== {name} ===")
    if not allrows:
        print("  (no cells parsed)")
        return
    overall = [r[1] + r[2] for r in allrows]
    print(f"  cells={len(allrows)}  mean|offset|(x+y)={np.mean(overall):.2f}px  "
          f"p95={np.percentile(overall,95):.2f}px")
    print(f"  {'col':>3} {'n':>4} {'mean|dx|':>9} {'p95|dx|':>8} {'mean|dy|':>9} {'p95|dy|':>8}")
    for c in range(5):
        cr = [r for r in allrows if r[0] == c]
        if not cr:
            continue
        dxs = [r[1] for r in cr]
        dys = [r[2] for r in cr]
        print(f"  {c+1:>3} {len(cr):>4} {np.mean(dxs):>9.2f} {np.percentile(dxs,95):>8.2f} "
              f"{np.mean(dys):>9.2f} {np.percentile(dys,95):>8.2f}")


def main():
    args = sys.argv[1:] or [r"C:\tmp\misaligned", r"C:\tmp\blurry"]
    csv_rows = []
    for a in args:
        paths = sorted(glob.glob(os.path.join(a, "answer_warped_decisions_*.png"))) \
            if os.path.isdir(a) else sorted(glob.glob(a))
        groupname = a
        group_all = []
        for p in paths:
            rows = measure(p)
            if not rows:
                continue
            group_all.extend(rows)
            overall = [r[1] + r[2] for r in rows]
            print(f"  {os.path.basename(p):55s} cells={len(rows):3d} "
                  f"score(mean|offset|)={np.mean(overall):.2f}px")
            csv_rows.append((os.path.basename(p), len(rows), f"{np.mean(overall):.2f}"))
        summarize(groupname, group_all)

    with open(OUT_CSV, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["file", "cells", "alignment_score_px"])
        w.writerows(csv_rows)
    print(f"\nwrote {OUT_CSV}")


if __name__ == "__main__":
    main()
