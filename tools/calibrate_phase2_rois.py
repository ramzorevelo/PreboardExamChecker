"""
calibrate_phase2_rois.py — offline calibration script for Phase 2 marker ROI tightening.

Input:  all *.jpg / *.png images in capture6/
Output: per-image debug images in capture6/debug/  (folder opened automatically)
        per-corner statistics + recommended ROI constants to the terminal

Pipeline matches AnswerZoneProcessor / MarkerDetector:
  BGR -> grayscale -> CLAHE(3.0, 32x32) -> Otsu THRESH_BINARY_INV -> morph close (7x7 ellipse)

Candidate filter:
  bbox area  in [0.10x, 3.0x] expected  where expected = (imgW * 0.048)^2
  aspect     in [0.5, 2.0]
  solidity   >= 0.55  (rejects bracket cells, text, hollow shapes)

Search zone per corner: outermost SEARCH_FRAC in both axes.
Selection: composite score = 0.60 * dist_norm + 0.40 * (1 - solidity).
  Lower = better. Solidity component breaks ties when two candidates are equidistant
  from the corner (e.g. one real marker + one slightly-closer bracket cell).
Ambiguity: if 2nd-best composite score < best * AMBIGUITY_RATIO, the corner is skipped
  for that image rather than guessing (keeps statistics clean).

Recommended ROI constants: mean +/- STATS_SIGMA sigma per cx/imgW, cy/imgH, clamped [0,1].
Debug images: search zones (thin rect), detected bounding boxes + circles, and the
  recommended tight ROI rectangle (thick rect) overlaid after stats are computed.
"""

import os
import subprocess
import sys

import cv2
import numpy as np

# ── Paths ──────────────────────────────────────────────────────────────────────

SCRIPT_DIR  = os.path.dirname(os.path.abspath(__file__))
CAPTURE_DIR = os.path.normpath(os.path.join(SCRIPT_DIR, '..', 'capture6'))
DEBUG_DIR   = os.path.join(CAPTURE_DIR, 'debug')

# ── Configuration ──────────────────────────────────────────────────────────────

# Phase 2 landscape naming: TM=top-left, TR=top-right, BM=bottom-left, BR=bottom-right
CORNER_NAMES = ['TM', 'TR', 'BM', 'BR']

# BGR colors (OpenCV convention)
CORNER_COLORS = {
    'TM': (0,   0,   255),   # red
    'TR': (0,   200,  0),    # green
    'BM': (255,  0,   0),    # blue
    'BR': (0,   200, 255),   # yellow-orange
}

# (x_frac, y_frac) of the image corner each marker hugs
CORNER_IDEAL = {
    'TM': (0.0, 0.0),
    'TR': (1.0, 0.0),
    'BM': (0.0, 1.0),
    'BR': (1.0, 1.0),
}

# Zone width: outermost SEARCH_FRAC in both axes per corner.
# 0.35 covers 79/80 observed TM positions (the extreme outlier at cy=0.384 would need
# 0.40, but that zone is packed with bracket distractors and hurts all other corners).
SEARCH_FRAC = 0.35

# Candidate shape filters
AREA_LO     = 0.10   # fraction of expected area (lower bound)
AREA_HI     = 3.00   # fraction of expected area (upper bound)
SOL_MIN     = 0.55   # contour area / bbox area; rejects text, hollow blobs

# Selection: composite_score = DIST_W * dist_norm + SOL_W * (1 - solidity)
# dist_norm = dist_to_corner / image_diagonal.  Lower score = better candidate.
DIST_W      = 0.60
SOL_W       = 0.40

# Two candidates are ambiguous (corner skipped) when:
#   score_2nd < score_best * AMBIGUITY_RATIO
# i.e. 2nd-best is less than (AMBIGUITY_RATIO-1)*100% worse than best.
AMBIGUITY_RATIO = 1.20

STATS_SIGMA = 2.5   # ROI bounds = mean +/- STATS_SIGMA * std


# ── Helpers ────────────────────────────────────────────────────────────────────

def search_roi(imgW, imgH, corner):
    """(x0, y0, x1, y1) search zone for corner in pixel coords."""
    bx = int(imgW * SEARCH_FRAC)
    by = int(imgH * SEARCH_FRAC)
    if corner == 'TM':
        return 0,        0,        bx,    by
    if corner == 'TR':
        return imgW-bx,  0,        imgW,  by
    if corner == 'BM':
        return 0,        imgH-by,  bx,    imgH
    if corner == 'BR':
        return imgW-bx,  imgH-by,  imgW,  imgH
    raise ValueError(corner)


def composite_score(cx, cy, imgW, imgH, corner, sol):
    """Lower score = better candidate. Combines distance and solidity."""
    ix, iy = CORNER_IDEAL[corner]
    dist     = float(np.hypot(cx - ix * imgW, cy - iy * imgH))
    diag     = float(np.hypot(imgW, imgH))
    dist_norm = dist / diag
    return DIST_W * dist_norm + SOL_W * (1.0 - sol)


# ── Per-image processing ───────────────────────────────────────────────────────

def process_image(img_path):
    """
    Returns dict with keys: filename, imgW, imgH, detections, debug.
    detections maps corner -> {cx_frac, cy_frac, size_frac, bbox, cx_px, cy_px}.
    Returns None on file-read failure.
    """
    img = cv2.imread(img_path)
    if img is None:
        print(f"  [WARN] cannot read: {img_path}")
        return None

    imgH, imgW = img.shape[:2]
    filename   = os.path.basename(img_path)

    # ── Preprocessing (matches AnswerZoneProcessor / MarkerDetector) ──────────
    gray     = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    clahe    = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(32, 32))
    enhanced = clahe.apply(gray)
    _, binary = cv2.threshold(enhanced, 0, 255,
                              cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7))
    binary = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, kernel)

    # ── Candidate search ───────────────────────────────────────────────────────
    side          = imgW * 0.048
    expected_area = side * side
    area_min      = expected_area * AREA_LO
    area_max      = expected_area * AREA_HI

    cnts, _ = cv2.findContours(binary.copy(), cv2.RETR_EXTERNAL,
                                cv2.CHAIN_APPROX_SIMPLE)
    candidates = []
    for cnt in cnts:
        x, y, w, h = cv2.boundingRect(cnt)
        bbox_area  = w * h
        if bbox_area < area_min or bbox_area > area_max:
            continue
        if h == 0:
            continue
        asp = w / h
        if asp < 0.5 or asp > 2.0:
            continue
        sol = cv2.contourArea(cnt) / bbox_area
        if sol < SOL_MIN:
            continue
        candidates.append({
            'cx':     x + w / 2.0,
            'cy':     y + h / 2.0,
            'sol':    sol,
            'area_r': bbox_area / expected_area,
            'bbox':   (x, y, w, h),
        })

    # ── Per-corner assignment ──────────────────────────────────────────────────
    detections = {}
    for corner in CORNER_NAMES:
        x0, y0, x1, y1 = search_roi(imgW, imgH, corner)
        zone = [c for c in candidates
                if x0 <= c['cx'] < x1 and y0 <= c['cy'] < y1]

        if not zone:
            print(f"  [WARN] {filename}: no candidate for {corner}")
            continue

        zone.sort(key=lambda c: composite_score(
            c['cx'], c['cy'], imgW, imgH, corner, c['sol']))
        best_s = composite_score(
            zone[0]['cx'], zone[0]['cy'], imgW, imgH, corner, zone[0]['sol'])

        if len(zone) > 1:
            sec_s = composite_score(
                zone[1]['cx'], zone[1]['cy'], imgW, imgH, corner, zone[1]['sol'])
            if sec_s < best_s * AMBIGUITY_RATIO:
                print(f"  [WARN] {filename}: ambiguous {corner} "
                      f"(score best={best_s:.3f} 2nd={sec_s:.3f}) — skipped")
                continue

        best = zone[0]
        detections[corner] = {
            'cx_frac':   best['cx'] / imgW,
            'cy_frac':   best['cy'] / imgH,
            'size_frac': float(np.sqrt(best['area_r'] * expected_area)) / imgW,
            'bbox':      best['bbox'],
            'cx_px':     best['cx'],
            'cy_px':     best['cy'],
        }

    # ── Build base debug image (search zones + detections; tight ROI added later) ──
    debug = img.copy()

    for corner in CORNER_NAMES:
        x0, y0, x1, y1 = search_roi(imgW, imgH, corner)
        cv2.rectangle(debug, (x0, y0), (x1-1, y1-1), CORNER_COLORS[corner], 1)

    for corner, det in detections.items():
        color = CORNER_COLORS[corner]
        bx, by, bw, bh = det['bbox']
        cv2.rectangle(debug, (bx, by), (bx+bw, by+bh), color, 2)
        cv2.circle(debug, (int(det['cx_px']), int(det['cy_px'])), 8, color, -1)
        cv2.circle(debug, (int(det['cx_px']), int(det['cy_px'])), 8, (255,255,255), 1)
        cv2.putText(debug, corner,
                    (bx + 2, by + bh + 14),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 1, cv2.LINE_AA)

    return {
        'filename':   filename,
        'imgW':       imgW,
        'imgH':       imgH,
        'detections': detections,
        'debug':      debug,
    }


# ── Statistics ─────────────────────────────────────────────────────────────────

def compute_stats(all_results):
    """Returns dict corner -> {cx_lo, cx_hi, cy_lo, cy_hi, n, ...} or {'n':0}."""
    per = {c: {'cx': [], 'cy': [], 'size': []} for c in CORNER_NAMES}
    for r in all_results:
        for corner, det in r['detections'].items():
            per[corner]['cx'].append(det['cx_frac'])
            per[corner]['cy'].append(det['cy_frac'])
            per[corner]['size'].append(det['size_frac'])

    stats = {}
    for corner in CORNER_NAMES:
        cx  = np.array(per[corner]['cx'])
        cy  = np.array(per[corner]['cy'])
        sz  = np.array(per[corner]['size'])
        n   = len(cx)
        if n == 0:
            stats[corner] = {'n': 0}
            continue
        cx_lo = float(np.clip(cx.mean() - STATS_SIGMA * cx.std(), 0.0, 1.0))
        cx_hi = float(np.clip(cx.mean() + STATS_SIGMA * cx.std(), 0.0, 1.0))
        cy_lo = float(np.clip(cy.mean() - STATS_SIGMA * cy.std(), 0.0, 1.0))
        cy_hi = float(np.clip(cy.mean() + STATS_SIGMA * cy.std(), 0.0, 1.0))
        stats[corner] = {
            'n':       n,
            'cx_mean': float(cx.mean()),  'cx_std': float(cx.std()),
            'cy_mean': float(cy.mean()),  'cy_std': float(cy.std()),
            'sz_mean': float(sz.mean()),  'sz_std': float(sz.std()),
            'cx_lo': cx_lo, 'cx_hi': cx_hi,
            'cy_lo': cy_lo, 'cy_hi': cy_hi,
        }
    return stats


def print_stats(stats):
    W = 76
    print()
    print('=' * W)
    print('PER-CORNER STATISTICS  (fractions of image dimensions)')
    print('=' * W)
    hdr = (f"{'Corner':<7} {'N':>4}  {'cx mean':>8} {'+-std':>7}  "
           f"{'cy mean':>8} {'+-std':>7}  {'size mean':>9} {'+-std':>7}")
    print(hdr)
    print('-' * W)
    for corner in CORNER_NAMES:
        s = stats[corner]
        if s['n'] == 0:
            print(f"{corner:<7} {0:>4}  (no detections)")
            continue
        print(f"{corner:<7} {s['n']:>4}  "
              f"{s['cx_mean']:>8.4f} {s['cx_std']:>7.4f}  "
              f"{s['cy_mean']:>8.4f} {s['cy_std']:>7.4f}  "
              f"{s['sz_mean']:>9.4f} {s['sz_std']:>7.4f}")

    print()
    print('=' * W)
    print(f'RECOMMENDED ROI CONSTANTS  (mean +/- {STATS_SIGMA} sigma, clamped [0,1])')
    print('Each ROI: cx_lo, cx_hi, cy_lo, cy_hi as fractions of image W/H')
    print('=' * W)
    for corner in CORNER_NAMES:
        s = stats[corner]
        if s['n'] == 0:
            print(f"  {corner}: no data")
            continue
        print(f"  {corner}:  cx=[{s['cx_lo']:.4f}, {s['cx_hi']:.4f}]  "
              f"cy=[{s['cy_lo']:.4f}, {s['cy_hi']:.4f}]  "
              f"size={s['sz_mean']:.4f} +/- {s['sz_std']:.4f}  (n={s['n']})")
    print()


# ── Overlay tight ROIs and save debug images ───────────────────────────────────

def finalize_debug(all_results, stats):
    os.makedirs(DEBUG_DIR, exist_ok=True)
    for r in all_results:
        debug = r['debug']
        imgW, imgH = r['imgW'], r['imgH']
        for corner in CORNER_NAMES:
            s = stats[corner]
            if s['n'] == 0:
                continue
            x0 = int(s['cx_lo'] * imgW)
            y0 = int(s['cy_lo'] * imgH)
            x1 = int(s['cx_hi'] * imgW)
            y1 = int(s['cy_hi'] * imgH)
            color = CORNER_COLORS[corner]
            cv2.rectangle(debug, (x0, y0), (x1, y1), color, 3)
            cv2.putText(debug, f'{corner} ROI',
                        (x0 + 2, y0 + 12),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.45, color, 1, cv2.LINE_AA)

        out_path = os.path.join(DEBUG_DIR, r['filename'])
        cv2.imwrite(out_path, debug)


# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    if not os.path.isdir(CAPTURE_DIR):
        sys.exit(f"capture directory not found: {CAPTURE_DIR}")

    exts = ('.jpg', '.jpeg', '.png')
    img_paths = sorted(
        os.path.join(CAPTURE_DIR, f)
        for f in os.listdir(CAPTURE_DIR)
        if f.lower().endswith(exts) and os.path.isfile(os.path.join(CAPTURE_DIR, f))
    )

    if not img_paths:
        sys.exit(f"no images found in {CAPTURE_DIR}")

    print(f"Processing {len(img_paths)} images from {CAPTURE_DIR}")
    print(f"  zone={SEARCH_FRAC}  sol>={SOL_MIN}  area=[{AREA_LO}x,{AREA_HI}x]  "
          f"ambig_ratio={AMBIGUITY_RATIO}")
    print()

    all_results = []
    for path in img_paths:
        print(f"  {os.path.basename(path)}")
        result = process_image(path)
        if result is not None:
            all_results.append(result)

    stats = compute_stats(all_results)
    print_stats(stats)

    finalize_debug(all_results, stats)
    print(f"Debug images saved to: {DEBUG_DIR}")
    print()

    subprocess.Popen(['explorer', os.path.normpath(DEBUG_DIR)])
    print("Debug folder opened.")


if __name__ == '__main__':
    main()
