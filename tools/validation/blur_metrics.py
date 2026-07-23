"""
blur_metrics.py — calibrate the content-aware blur gate (plan Part B5).

Measures Laplacian variance on two classes of Phase-2 debug images:
  - BLURRY  : C:\\tmp\\blurry\\*.png            (frames that should be rejected)
  - SHARP   : C:\\tmp\\misaligned\\*.png +      (in-focus, so they double as the
              C:\\tmp\\capture{2..6}\\answer_warped_decisions_*.png   sharp class)

For each image it reports variance on:
  - full     : whole grayscale frame
  - band     : the dense answer-grid band only (y from GRID_TOP_FRAC..1.0, full
               width) — this mirrors the on-device measurement, which runs on the
               warped answer grid, NOT the blank-paper center crop the old still
               gate used.

CAVEAT: these are `answer_warped_decisions` OVERLAY images (green/red annotations
drawn on top), NOT clean warps. Both classes carry the same overlay, so the
SEPARATION between classes is meaningful, but the ABSOLUTE floor printed here is
only a starting point — confirm ANSWER_BLUR_VARIANCE_MIN against the on-device
"answer-grid variance=" Log.d before enabling ENABLE_CONTENT_BLUR_GATE.

Outputs: a printed table + C:\\tmp\\cal\\blur_metrics.csv
"""

import csv
import glob
import os

import cv2
import numpy as np

GRID_TOP_FRAC = 0.10  # skip the subject header strip; grid starts ~here

BLURRY_GLOBS = [r"C:\tmp\blurry\*.png"]
SHARP_GLOBS = [
    r"C:\tmp\misaligned\*.png",
    r"C:\tmp\capture2\answer_warped_decisions_*.png",
    r"C:\tmp\capture3\answer_warped_decisions_*.png",
    r"C:\tmp\capture4\answer_warped_decisions_*.png",
    r"C:\tmp\capture5\answer_warped_decisions_*.png",
    r"C:\tmp\capture6\answer_warped_decisions_*.png",
]

OUT_CSV = r"C:\tmp\cal\blur_metrics.csv"


def lap_var(gray):
    return float(cv2.Laplacian(gray, cv2.CV_64F).var())


def measure(path):
    img = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    if img is None:
        return None
    h = img.shape[0]
    band = img[int(GRID_TOP_FRAC * h):, :]
    return lap_var(img), lap_var(band)


def gather(globs):
    rows = []
    for g in globs:
        for p in sorted(glob.glob(g)):
            m = measure(p)
            if m is not None:
                rows.append((os.path.basename(p), m[0], m[1]))
    return rows


def pct(vals, q):
    return float(np.percentile(vals, q)) if vals else float("nan")


def summarize(name, rows):
    band = [r[2] for r in rows]
    print(f"\n=== {name} (n={len(rows)}) — BAND variance ===")
    for fn, full, b in rows:
        print(f"  {fn:55s} full={full:9.1f}  band={b:9.1f}")
    if band:
        print(f"  -- band p5={pct(band,5):.1f} p25={pct(band,25):.1f} "
              f"p50={pct(band,50):.1f} p75={pct(band,75):.1f} p95={pct(band,95):.1f} "
              f"min={min(band):.1f} max={max(band):.1f}")
    return band


def main():
    blurry = gather(BLURRY_GLOBS)
    sharp = gather(SHARP_GLOBS)

    b_band = summarize("BLURRY (reject)", blurry)
    s_band = summarize("SHARP (accept)", sharp)

    # Recommended floor: midpoint between the blurry-max and sharp-min if they
    # separate cleanly; otherwise bias toward rejecting (catch blur) by sitting
    # near the sharp p5.
    print("\n=== RECOMMENDATION (band variance) ===")
    if b_band and s_band:
        b_hi = max(b_band)
        s_lo = min(s_band)
        if b_hi < s_lo:
            floor = (b_hi + s_lo) / 2.0
            print(f"  clean separation: blurry_max={b_hi:.1f} < sharp_min={s_lo:.1f}")
        else:
            floor = pct(s_band, 5)
            print(f"  OVERLAP: blurry_max={b_hi:.1f} >= sharp_min={s_lo:.1f} "
                  f"(overlay edges inflate blurry); biasing to sharp p5")
        fa = sum(1 for v in b_band if v >= floor)  # blurry wrongly accepted
        fr = sum(1 for v in s_band if v < floor)   # sharp wrongly rejected
        print(f"  suggested ANSWER_BLUR_VARIANCE_MIN ~= {floor:.0f}")
        print(f"  at that floor: false-accept(blurry passes)={fa}/{len(b_band)}  "
              f"false-reject(sharp fails)={fr}/{len(s_band)}")
        print("  NOTE: overlay images inflate variance vs clean warps — treat as "
              "a starting point; confirm on-device.")

    with open(OUT_CSV, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["class", "file", "full_var", "band_var"])
        for fn, full, b in blurry:
            w.writerow(["blurry", fn, f"{full:.1f}", f"{b:.1f}"])
        for fn, full, b in sharp:
            w.writerow(["sharp", fn, f"{full:.1f}", f"{b:.1f}"])
    print(f"\nwrote {OUT_CSV}")


if __name__ == "__main__":
    main()
