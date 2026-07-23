#!/usr/bin/env python3
"""
SheetBlueprint.kt answer-zone calibration script.
Input:  C:\\tmp\\cal\\sheet-1.png  (3900x2550, 300 DPI PDF render)
Output: Kotlin constant block + debug PNGs in C:\\tmp\\cal\\
"""

import cv2
import numpy as np
import os, sys
from datetime import date
from scipy.signal import find_peaks

INPUT   = r'C:\tmp\cal\sheet-1.png'
OUTDIR  = r'C:\tmp\cal'
WARP_W  = 2400
WARP_H  = 1500
COL_SUBWARP_LONG = 1600   # normalise longer side to this

# Legacy SheetBlueprint constants (calibrated for 2400×1400 warp):
LEGACY_BLOCK_ANCHOR_X = [173, 641, 1109, 1577, 2045]
LEGACY_CHOICE_PITCH   = 58
LEGACY_ROW_Y = [182, 235, 287, 338, 390, 442, 493, 546, 598, 650,
                732, 785, 837, 888, 940, 992, 1043, 1096, 1148, 1200]
LEGACY_SUBJ_X = [855, 1350, 1845]   # subject bubble x in 2400×(any) warp

# Scale row positions to the new 1500-px warp height
SCALE_Y = WARP_H / 1400.0
ROW_Y_1500 = [y * SCALE_Y for y in LEGACY_ROW_Y]
# Average inter-row stride (within each sub-group of 10)
STRIDES_1500  = [(ROW_Y_1500[i+1] - ROW_Y_1500[i]) for i in range(9)] + \
               [(ROW_Y_1500[i+1] - ROW_Y_1500[i]) for i in range(10, 19)]
MEAN_STRIDE   = float(np.mean(STRIDES_1500))      # ~55.7 px in 1500-warp
SUBGROUP_GAP  = ROW_Y_1500[10] - ROW_Y_1500[9]   # gap between row 9 and 10

# Helpers ─────────────────────────────────────────────────────────────────────

def fmt(pt): return f"({pt[0]:.1f},{pt[1]:.1f})"

def smooth1d(a, half_win=5):
    k = np.ones(2*half_win+1) / (2*half_win+1)
    return np.convolve(a.astype(float), k, mode='same')

def find_valleys(signal, min_dist=30):
    """Return indices of local minima below 70% of signal max."""
    thr = signal.max() * 0.70
    valleys = []
    n = len(signal)
    for i in range(1, n-1):
        if signal[i] < thr and signal[i] <= signal[i-1] and signal[i] <= signal[i+1]:
            if not valleys or (i - valleys[-1]) >= min_dist:
                valleys.append(i)
            elif signal[i] < signal[valleys[-1]]:
                valleys[-1] = i
    return valleys

def save(name, img):
    cv2.imwrite(os.path.join(OUTDIR, name), img)
    print(f"  Saved {name}")

# ── A. Load + detect 6 markers ────────────────────────────────────────────────

img_bgr = cv2.imread(INPUT)
assert img_bgr is not None, f"Cannot read {INPUT}"
H_img, W_img = img_bgr.shape[:2]
print(f"Image: {W_img}x{H_img}")

gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
clahe_m = cv2.createCLAHE(3.0, (32,32))
_, binary = cv2.threshold(clahe_m.apply(gray), 0, 255,
                           cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)

# Reference-PDF markers: solid squares ≈84×84 px (area ≈7056) at 300 DPI
cnts, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
cands = []
for cnt in cnts:
    bx, by, bw, bh = cv2.boundingRect(cnt)
    ba = bw * bh
    if ba < 3000 or ba > 15000: continue
    if bw/bh < 0.6 or bw/bh > 1.6: continue
    if cv2.contourArea(cnt) / ba < 0.75: continue
    cands.append((bx+bw/2.0, by+bh/2.0, ba))

cands.sort(key=lambda c: c[2], reverse=True)

# Find y-split via largest gap between consecutive sorted-y values
cy_sorted = sorted(cands[:20], key=lambda c: c[1])
ys = [c[1] for c in cy_sorted]
split_idx = max(range(len(ys)-1), key=lambda i: ys[i+1]-ys[i]) + 1
split_y   = (ys[split_idx-1] + ys[split_idx]) / 2
print(f"  {len(cands)} marker candidates, row split at y={split_y:.0f}")

top3 = sorted([c for c in cands[:20] if c[1] < split_y], key=lambda c: c[0])[:3]
bot3 = sorted([c for c in cands[:20] if c[1] >= split_y], key=lambda c: c[0])[:3]

if len(top3) < 3 or len(bot3) < 3:
    print(f"ERROR: only top={len(top3)} bot={len(bot3)} markers."); sys.exit(1)

TL,TM,TR = (top3[0][:2],top3[1][:2],top3[2][:2])
BL,BM,BR = (bot3[0][:2],bot3[1][:2],bot3[2][:2])
print(f"TL={fmt(TL)}  TM={fmt(TM)}  TR={fmt(TR)}")
print(f"BL={fmt(BL)}  BM={fmt(BM)}  BR={fmt(BR)}")

# Alignment sanity
for name, a, b, c in [("top-row", TL, TM, TR), ("bot-row", BL, BM, BR)]:
    if abs(a[1]-b[1]) > 50 or abs(b[1]-c[1]) > 50:
        print(f"ERROR: {name} markers not horizontally aligned."); sys.exit(1)

# ── B. Warp answer zone to 2400×1500 ─────────────────────────────────────────
src = np.float32([TM, TR, BM, BR])
dst = np.float32([(0,0),(WARP_W,0),(0,WARP_H),(WARP_W,WARP_H)])
M   = cv2.getPerspectiveTransform(src, dst)
warp_gray = cv2.warpPerspective(gray, M, (WARP_W, WARP_H))
save('warped_answer.png', warp_gray)

# Binary (same as AnswerZoneProcessor.computeColumnBinary)
clahe2   = cv2.createCLAHE(2.0, (8,8))
warp_enh = clahe2.apply(warp_gray)
warp_bin = cv2.adaptiveThreshold(warp_enh, 255,
               cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 51, 8)
k3 = cv2.getStructuringElement(cv2.MORPH_RECT, (3,3))
warp_bin = cv2.morphologyEx(warp_bin, cv2.MORPH_CLOSE, k3)
# dark content = 0, background = 255

# ── C1. Subject strip ─────────────────────────────────────────────────────────
# Detect subject-cell y by sampling horizontal projection ONLY in narrow x-strips
# around each expected subject-bubble x-position (avoids contamination from the
# top border line which spans the full width).
print("\n[Subject strip]")
SUBJ_SEARCH_Y0, SUBJ_SEARCH_Y1 = 55, 180
STRIP_HALF_W = 80

subj_strip_ys = []
for exp_x in LEGACY_SUBJ_X:
    x0s = max(0, exp_x - STRIP_HALF_W)
    x1s = min(WARP_W, exp_x + STRIP_HALF_W)
    strip_h = np.sum(warp_bin[SUBJ_SEARCH_Y0:SUBJ_SEARCH_Y1, x0s:x1s] == 0, axis=1).astype(float)
    strip_h_sm = smooth1d(strip_h, half_win=3)
    strip_h_sm[:10] = 0   # skip top-edge border artifact
    peak_local = int(np.argmax(strip_h_sm))
    peak_global = peak_local + SUBJ_SEARCH_Y0
    subj_strip_ys.append(peak_global)
    print(f"  exp_x={exp_x}: peak_y={peak_global}")

# Use legacy x-centers directly (calibrated for WARP_W=2400, unchanged)
subj_det_xs = list(LEGACY_SUBJ_X)

print(f"  Subject y-peaks: {subj_strip_ys}")
print(f"  Subject x-centers (legacy): {subj_det_xs}")

# Subject strip y band: ±3 strides from the subject y center
subj_y_mid_px = int(np.mean(subj_strip_ys))
# The subject strip brackets roughly the full "SUBJECT" row:
# typical cell height ≈ 0.7 × mean_stride of answer rows
subj_cell_h_guess = MEAN_STRIDE * 1.2

# Find the actual extent using y-strip projection in the detected x-band
# (use center subject bubble x)
cx_s = subj_det_xs[1]   # ESAS bubble, near center
y_lo = max(0, subj_y_mid_px - 100)   # extend 100 px above bottom bracket
y_hi = min(WARP_H, subj_y_mid_px + 25)
h_proj_s = np.sum(warp_bin[y_lo:y_hi, cx_s-STRIP_HALF_W:cx_s+STRIP_HALF_W] == 0, axis=1).astype(float)
h_sm_s   = smooth1d(h_proj_s, half_win=3)
thr_s    = h_sm_s.max() * 0.08 if h_sm_s.max() > 0 else 1
active_s = np.where(h_sm_s > thr_s)[0]
if len(active_s) > 0:
    band_start = int(active_s[0]) + y_lo
    band_end   = int(active_s[-1]) + y_lo
    band_end = min(band_end, int(WARP_H * 0.088))  # cap at ~132 px, before col_y0≈143
else:
    band_start = subj_y_mid_px - 15
    band_end   = subj_y_mid_px + 15

# Minimum band height of 15 px
if (band_end - band_start) < 30:
    band_start = subj_y_mid_px - 15
    band_end   = subj_y_mid_px + 15

MARG_S = 14
subj_y0 = max(0, band_start - MARG_S)
subj_y1 = min(WARP_H, band_end + 30)   # tighter bottom margin only
SUBJECT_FALLBACK_Y_FRAC = (band_start + band_end) / 2.0 / WARP_H + 0.004
SUBJECT_STRIP_Y_FRAC_START = subj_y0 / WARP_H
SUBJECT_STRIP_Y_FRAC_END   = subj_y1 / WARP_H

# Subject cell size: measure from the binary in the detected strip
# Width: cluster extent around the peak x
v_strip = np.sum(warp_bin[subj_y0:subj_y1, :] == 0, axis=0).astype(float)
v_strip_sm = smooth1d(v_strip, half_win=5)
def cluster_w(sig, cx, frac=0.25):
    thr = sig[cx] * frac
    lo, hi = cx, cx
    while lo > 0 and sig[lo] > thr: lo -= 1
    while hi < len(sig)-1 and sig[hi] > thr: hi += 1
    return hi - lo

cell_h_px = band_end - band_start
# W: the cluster_w() measurement is unreliable (border contamination spans full width).
# Use the same bracket-cell size as the answer cells, scaled to the full warp space:
#   answer cell w in full-warp px = CELL_FILL_RATIO * LEGACY_CHOICE_PITCH
#   (the ANSWER_COL_WARP normalisation cancels)
# This is computed after avg_col_w is known; set a placeholder here, overwritten below.
SUBJECT_CELL_W_FRAC = LEGACY_CHOICE_PITCH / WARP_W * 0.43
SUBJECT_CELL_H_FRAC = min(0.027, max(0.011, (cell_h_px * 1.2) / WARP_H))

print(f"  Band y=[{band_start},{band_end}] ->strip y=[{subj_y0},{subj_y1}]")
print(f"  SUBJECT_STRIP_Y_FRAC_START = {SUBJECT_STRIP_Y_FRAC_START:.4f}")
print(f"  SUBJECT_STRIP_Y_FRAC_END   = {SUBJECT_STRIP_Y_FRAC_END:.4f}")
print(f"  SUBJECT_FALLBACK_Y_FRAC    = {SUBJECT_FALLBACK_Y_FRAC:.4f}")
print(f"  cell_w_px={int(SUBJECT_CELL_W_FRAC * WARP_W)}  cell_h_px={cell_h_px}")
print(f"  SUBJECT_CELL_W_FRAC = {SUBJECT_CELL_W_FRAC:.4f}")
print(f"  SUBJECT_CELL_H_FRAC = {SUBJECT_CELL_H_FRAC:.4f}")

# Detect actual subject box bottom border line: last full-width dark horizontal
# line between the subject bubbles and the answer grid (y = 80..175).
SUBJ_BOX_BOTTOM_Y = int(SUBJECT_STRIP_Y_FRAC_END * WARP_H)  # fallback
for _y in range(80, min(155, WARP_H)):
    if np.sum(warp_bin[_y, :] == 0) > WARP_W * 0.60:
        SUBJ_BOX_BOTTOM_Y = _y
SUBJECT_BOX_BOTTOM_FRAC = SUBJ_BOX_BOTTOM_Y / WARP_H
print(f"  Subject box bottom border: y={SUBJ_BOX_BOTTOM_Y}  frac={SUBJECT_BOX_BOTTOM_FRAC:.4f}")

# ── C2. Column x-bounds (from vertical projection gaps) ───────────────────────
print("\n[Column x-bounds]")
ans_y0_search = subj_y1 + 30
v_ans = np.sum(warp_bin[ans_y0_search:, :] == 0, axis=0).astype(float)
v_sm  = smooth1d(v_ans, half_win=8)

# Correct gap search: midpoint between right edge of col i and left edge of col i+1
gap_search_xs = [
    (LEGACY_BLOCK_ANCHOR_X[i] + 4*LEGACY_CHOICE_PITCH + LEGACY_CHOICE_PITCH//2 +
     LEGACY_BLOCK_ANCHOR_X[i+1] - LEGACY_CHOICE_PITCH//2) // 2
    for i in range(4)
]   # → [523, 991, 1459, 1927]
print(f"  Inter-column gap search positions: {gap_search_xs}")

WIN = 80
gap_xs = []
for gx in gap_search_xs:
    lo, hi = max(0, gx - WIN), min(WARP_W, gx + WIN)
    gap_xs.append(lo + int(np.argmin(v_sm[lo:hi])))
print(f"  Detected gap positions: {gap_xs}")

# Gap-based initial ranges — wide enough to contain actual box borders
col_x_ranges = []
for i in range(5):
    x0 = gap_xs[i-1] if i > 0 else 0
    x1 = gap_xs[i]   if i < 4 else WARP_W - 10
    col_x_ranges.append((int(x0), int(x1)))
    print(f"  Col {i+1}: x=[{int(x0)},{int(x1)}]")

# ── C3. Column y-bounds (derived from legacy row positions scaled to 1500) ────
# The legacy rowYCenters are calibrated positions in the 2400×1400 warp.
# The answer-grid y range in the 1500-px warp:
row_stride_1500 = MEAN_STRIDE   # ≈55.7 px
row_y0_1500 = ROW_Y_1500[0]   - row_stride_1500 * 0.6   # half-stride above row 1

COL_MARGIN_Y = 0
col_y0 = max(0, int(row_y0_1500) - COL_MARGIN_Y + 5) 
print(f"\n[Column y-bounds]  row_y0={row_y0_1500:.1f}")

# Detect the actual bottom border of the column group boxes
col_bottom_ys = []
for x0, x1 in col_x_ranges:
    search_start = int(WARP_H * 0.70)
    h_bot = np.sum(warp_bin[search_start:, x0:x1] == 0, axis=1).astype(float)
    h_sm  = smooth1d(h_bot, half_win=4)
    thr_b = h_sm.max() * 0.35 if h_sm.max() > 0 else 1
    active_b = np.where(h_sm > thr_b)[0]
    bot_y = (int(active_b[-1]) + search_start) if len(active_b) > 0 \
            else int(WARP_H * 0.93)
    col_bottom_ys.append(bot_y)
    print(f"  x=[{x0},{x1}] bottom_y={bot_y}")
col_y1 = min(WARP_H - 5, int(np.median(col_bottom_ys)) + 1)
print(f"  col_y1 (detected from image) = {col_y1}")
print(f"  col_y=[{col_y0},{col_y1}]  height={col_y1-col_y0}")

col_regions = [(x0, x1, col_y0, col_y1) for x0, x1 in col_x_ranges]

# Detect actual box left/right borders within each gap range
print("\n[Actual column x-borders]")
actual_col_x_ranges = []
for x_lo, x_hi in col_x_ranges:
    v_col = np.sum(warp_bin[col_y0:col_y1, x_lo:x_hi] == 0, axis=0).astype(float)
    v_sm2 = smooth1d(v_col, half_win=3)
    thr_v = v_sm2.max() * 0.30 if v_sm2.max() > 0 else 1
    active_v = np.where(v_sm2 > thr_v)[0]
    if len(active_v) >= 2:
        actual_left  = x_lo + int(active_v[0])
        actual_right = x_lo + int(active_v[-1])
    else:
        actual_left, actual_right = x_lo, x_hi
    actual_col_x_ranges.append((actual_left, actual_right))
    print(f"  x=[{x_lo},{x_hi}] → box=[{actual_left},{actual_right}]")

col_regions = [(x0, x1, col_y0, col_y1) for x0, x1 in actual_col_x_ranges]

# ── C4. Sub-warp dimensions and cell size ─────────────────────────────────────
# Use representative column (average of cols 1-4; col 5 may be slightly narrower)
avg_col_w = float(np.mean([r[1]-r[0] for r in col_regions[:4]]))
avg_col_h = float(col_y1 - col_y0)

if avg_col_h >= avg_col_w:
    ANSWER_COL_WARP_H = COL_SUBWARP_LONG
    ANSWER_COL_WARP_W = max(200, int(round(avg_col_w / avg_col_h * COL_SUBWARP_LONG)))
else:
    ANSWER_COL_WARP_W = COL_SUBWARP_LONG
    ANSWER_COL_WARP_H = max(200, int(round(avg_col_h / avg_col_w * COL_SUBWARP_LONG)))

print(f"\n  avg_col_w={avg_col_w:.1f}  avg_col_h={avg_col_h:.1f}")
print(f"  ANSWER_COL_WARP_W = {ANSWER_COL_WARP_W}")
print(f"  ANSWER_COL_WARP_H = {ANSWER_COL_WARP_H}")

# Cell size: legacy choice pitch and row stride mapped through the crop ->sub-warp transform.
# ANSWER_CELL_W_FRAC = 0.85 × (choice_pitch_px / crop_w) × ANSWER_COL_WARP_W / ANSWER_COL_WARP_W
#                    = 0.85 × choice_pitch_px / crop_w
# (the ANSWER_COL_WARP scaling cancels)
CELL_FILL_RATIO = 0.85
ANSWER_CELL_W_FRAC = CELL_FILL_RATIO * LEGACY_CHOICE_PITCH / avg_col_w
# y: stride in 1500-warp mapped to sub-warp fraction
ANSWER_CELL_H_FRAC = CELL_FILL_RATIO * row_stride_1500 / avg_col_h

print(f"  ANSWER_CELL_W_FRAC = {ANSWER_CELL_W_FRAC:.4f}  "
      f"(= 0.85 × {LEGACY_CHOICE_PITCH} / {avg_col_w:.1f})")
print(f"  ANSWER_CELL_H_FRAC = {ANSWER_CELL_H_FRAC:.4f}  "
      f"(= 0.85 × {row_stride_1500:.1f} / {avg_col_h:.1f})")

# answerColSearchRegions: 5 adjacent regions sharing dividers at detected gap positions.
# Outer margins only on far left and far right → exactly 6 vertical magenta lines.
MARG_X_OUTER_PX = 15
MARG_Y = 0.030
sx_bounds = (
    [max(0, actual_col_x_ranges[0][0] - MARG_X_OUTER_PX)] +
    gap_xs +
    [min(WARP_W, actual_col_x_ranges[4][1] + MARG_X_OUTER_PX)]
)
ans_search_regions = []
for i in range(5):
    ans_search_regions.append((
        sx_bounds[i]   / WARP_W,
        sx_bounds[i+1] / WARP_W,
        SUBJECT_BOX_BOTTOM_FRAC,
        min(1.0, col_y1 / WARP_H + MARG_Y),
    ))

print("\n[answerColSearchRegions]")
for i,(sx0,sx1,sy0,sy1) in enumerate(ans_search_regions):
    print(f"  col{i+1}: x=[{sx0:.4f},{sx1:.4f}]  y=[{sy0:.4f},{sy1:.4f}]  "
          f"px=[{int(sx0*WARP_W)},{int(sx1*WARP_W)}]×[{int(sy0*WARP_H)},{int(sy1*WARP_H)}]")

# ── D. Build debug overlays ────────────────────────────────────────────────────
print("\n[Debug overlays]")

# ─ warped_answer_boxes.png ─
ann = cv2.cvtColor(warp_gray, cv2.COLOR_GRAY2BGR)
# Subject strip: blue band
cv2.rectangle(ann, (0, subj_y0), (WARP_W, subj_y1), (255, 0, 0), 2)
# Subject cell rects: cyan
cw_px = max(4, int(SUBJECT_CELL_W_FRAC * WARP_W))
ch_px = max(4, int(SUBJECT_CELL_H_FRAC * WARP_H))
sy_mid = int(SUBJECT_FALLBACK_Y_FRAC * WARP_H)
for sx in subj_det_xs:
    cv2.rectangle(ann,
        (sx - cw_px//2, sy_mid - ch_px//2),
        (sx + cw_px//2, sy_mid + ch_px//2), (255, 255, 0), 2)
# Column search regions: magenta
for sx0,sx1,sy0_f,sy1_f in ans_search_regions:
    cv2.rectangle(ann,
        (int(sx0*WARP_W), int(sy0_f*WARP_H)),
        (int(sx1*WARP_W), int(sy1_f*WARP_H)), (255, 0, 255), 2)
# Column boxes: green
for x0,x1,y0,y1 in col_regions:
    cv2.rectangle(ann, (x0, y0), (x1, y1), (0, 255, 0), 3)
save('warped_answer_boxes.png', ann)

# ─ subject_subwarp_cells.png ─ (just the subject strip with cell boxes)
subj_strip_img = cv2.cvtColor(warp_gray[subj_y0:subj_y1, :], cv2.COLOR_GRAY2BGR)
for sx in subj_det_xs:
    cx_rel = sx
    cy_rel = sy_mid - subj_y0
    cv2.rectangle(subj_strip_img,
        (cx_rel - cw_px//2, cy_rel - ch_px//2),
        (cx_rel + cw_px//2, cy_rel + ch_px//2), (0, 255, 255), 2)
save('subject_subwarp_cells.png', subj_strip_img)

# ─ colN_subwarp_grid.png ─ for each column
SW = ANSWER_COL_WARP_W
SH = ANSWER_COL_WARP_H

all_choice_fracs = []
all_row_data = []   # list of (c1, c2) float center lists per column

def cluster_to_n(vals, n, max_gap_frac=0.5):
    """Merge values within max_gap of each other, return n group means."""
    if not vals: return []
    vals = sorted(vals)
    max_gap = (vals[-1] - vals[0]) * max_gap_frac / n
    groups, cur = [], [vals[0]]
    for v in vals[1:]:
        if v - cur[-1] < max_gap:
            cur.append(v)
        else:
            groups.append(sum(cur) / len(cur))
            cur = [v]
    groups.append(sum(cur) / len(cur))
    # If more groups than n, merge the closest pair repeatedly
    while len(groups) > n:
        diffs = [groups[i+1]-groups[i] for i in range(len(groups)-1)]
        i = diffs.index(min(diffs))
        groups[i] = (groups[i] + groups[i+1]) / 2
        groups.pop(i+1)
    return groups

for ci, (x0, x1, y0, y1) in enumerate(col_regions):
    cw = x1 - x0
    ch = y1 - y0

    crop_g = warp_gray[y0:y1, x0:x1]
    sub_g  = cv2.resize(crop_g, (SW, SH), interpolation=cv2.INTER_LINEAR)
    overlay = cv2.cvtColor(sub_g, cv2.COLOR_GRAY2BGR)

    # ── Row Y positions: detect bar pairs directly from sub-warp ─────────────
    # Each answer cell has exactly 2 full-width horizontal bars (top bracket bar
    # and bottom bracket bar). Find all ~40 bars, pair adjacent ones, midpoint
    # = cell center.  This avoids relying on any legacy row Y constants.
    row_dark = np.sum(sub_g < 128, axis=1).astype(float)
    bar_rows = np.where(row_dark > SW * 0.25)[0].tolist()

    # Cluster consecutive pixels into distinct bar positions
    bar_clusters = []
    if bar_rows:
        cluster = [bar_rows[0]]
        for yr in bar_rows[1:]:
            if yr - cluster[-1] <= 4:
                cluster.append(yr)
            else:
                bar_clusters.append(int(np.mean(cluster)))
                cluster = [yr]
        bar_clusters.append(int(np.mean(cluster)))

    if len(bar_clusters) == 40:
        # Pair bars: (bar0,bar1)=row1, (bar2,bar3)=row2, ...
        row_ys_sub = [(bar_clusters[i*2] + bar_clusters[i*2+1]) / 2.0
                      for i in range(20)]
        all_row_data.append((row_ys_sub[:10], row_ys_sub[10:]))
        print(f"  Col {ci+1} row detection OK: 40 bars → 20 centers "
              f"(sg1[0]={row_ys_sub[0]:.1f} sg2[0]={row_ys_sub[10]:.1f})")
    else:
        print(f"  WARNING col{ci+1}: found {len(bar_clusters)} bar clusters "
              f"(expected 40) — falling back to legacy mapping")
        row_ys_sub = [(ry - y0) / ch * SH for ry in ROW_Y_1500]

    # ── Choice X positions: analytic from SheetBlueprint ground truth ────────
    # The original vertical-projection approach peaked at bracket EDGE lines,
    # not bracket centers (interior of each bracket has few dark pixels).
    anchX = LEGACY_BLOCK_ANCHOR_X[ci]
    choice_xs_sub = [round((anchX + c * LEGACY_CHOICE_PITCH - x0) / cw * SW)
                     for c in range(5)]

    choice_x_fracs = [cx / SW for cx in choice_xs_sub]
    all_choice_fracs.append(choice_x_fracs)
    print(f"  Col {ci+1} choice_x_fracs = {[f'{v:.4f}' for v in choice_x_fracs]}")

    # Draw row lines (green = sg1, red = sg2)
    for ri, ry in enumerate(row_ys_sub):
        color = (0, 255, 0) if ri < 10 else (0, 0, 255)
        cv2.line(overlay, (0, int(ry)), (SW, int(ry)), color, 1)
    # Draw choice lines (detected)
    choice_colors = [(255,0,0),(0,255,0),(0,0,255),(0,255,255),(255,0,255)]
    for ci2, cx in enumerate(choice_xs_sub):
        cv2.line(overlay, (cx, 0), (cx, SH), choice_colors[ci2], 1)
    # Draw cell rects
    cell_w_sub = max(3, int(ANSWER_CELL_W_FRAC * SW))
    cell_h_sub = max(3, int(ANSWER_CELL_H_FRAC * SH))
    for ri, ry in enumerate(row_ys_sub):
        for ci2, cx in enumerate(choice_xs_sub):
            rx = max(0, cx - cell_w_sub//2)
            ry2= max(0, int(ry) - cell_h_sub//2)
            cv2.rectangle(overlay, (rx, ry2),
                          (min(SW-1,rx+cell_w_sub), min(SH-1,ry2+cell_h_sub)),
                          (200,200,200), 1)
    # Row labels
    for ri, ry in enumerate(row_ys_sub):
        cv2.putText(overlay, str(ri+1), (3, int(ry)+4),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.3, (200,200,200), 1)

    save(f'col{ci+1}_subwarp_grid.png', overlay)
    print(f"  Col {ci+1}: row_ys_sub={row_ys_sub[:3]}..{row_ys_sub[-1]}  "
          f"choice_xs_sub={choice_xs_sub}")

ANSWER_CHOICE_X_FRACS = [
    float(np.mean([col[i] for col in all_choice_fracs]))
    for i in range(5)
]
print(f"ANSWER_CHOICE_X_FRACS (avg) = {[f'{v:.4f}' for v in ANSWER_CHOICE_X_FRACS]}")

if all_row_data:
    ANSWER_ROW_FIRST_Y_FRAC = float(np.mean([d[0][0] for d in all_row_data])) / SH
    ANSWER_ROW_STRIDE_FRAC  = float(np.mean(
        [np.mean(np.diff(d[0])) for d in all_row_data])) / SH
    ANSWER_SUBGROUP_GAP_FRAC = float(np.mean(
        [d[1][0] - d[0][-1] for d in all_row_data])) / SH - ANSWER_ROW_STRIDE_FRAC
    print(f"ANSWER_ROW_FIRST_Y_FRAC  = {ANSWER_ROW_FIRST_Y_FRAC:.4f}")
    print(f"ANSWER_ROW_STRIDE_FRAC   = {ANSWER_ROW_STRIDE_FRAC:.4f}")
    print(f"ANSWER_SUBGROUP_GAP_FRAC = {ANSWER_SUBGROUP_GAP_FRAC:.4f}")
else:
    ANSWER_ROW_FIRST_Y_FRAC = ANSWER_ROW_STRIDE_FRAC = ANSWER_SUBGROUP_GAP_FRAC = None
    print("WARNING: no valid row detections -- row fracs not computed")

# ── E. Print Kotlin constants ─────────────────────────────────────────────────
today = date.today().isoformat()
subj_x_fracs = [float(round(sx / WARP_W, 4)) for sx in subj_det_xs]
print()
print("=" * 70)
print("KOTLIN — paste into SheetBlueprint.kt  (answer zone TODO section)")
print("=" * 70)
print(f"\n    // Calibrated {today} from Catc_answer_sheetV4.pdf at 300 DPI")
print(f"    const val ANSWER_COL_WARP_W = {ANSWER_COL_WARP_W}")
print(f"    const val ANSWER_COL_WARP_H = {ANSWER_COL_WARP_H}")
print(f"\n    const val SUBJECT_STRIP_Y_FRAC_START = {SUBJECT_STRIP_Y_FRAC_START:.4f}f")
print(f"    const val SUBJECT_STRIP_Y_FRAC_END   = {SUBJECT_STRIP_Y_FRAC_END:.4f}f")
print(f"\n    const val SUBJECT_CELL_W_FRAC = {SUBJECT_CELL_W_FRAC:.4f}f")
print(f"    const val SUBJECT_CELL_H_FRAC = {SUBJECT_CELL_H_FRAC:.4f}f")
print(f"\n    const val SUBJECT_FALLBACK_Y_FRAC = {SUBJECT_FALLBACK_Y_FRAC:.4f}f")
print(f"\n    // Detected subject x-fracs (informational — "
      f"subjectFallbackXFracs already set): {subj_x_fracs}")
print(f"\n    val answerColSearchRegions: Array<Pair<ClosedFloatingPointRange<Float>, ClosedFloatingPointRange<Float>>> = arrayOf(")
labels = ['Q 1-20', 'Q21-40', 'Q41-60', 'Q61-80', 'Q81-100']
for i,(sx0,sx1,sy0_f,sy1_f) in enumerate(ans_search_regions):
    comma = "," if i < 4 else ""
    print(f"        ({sx0:.4f}f..{sx1:.4f}f) to ({sy0_f:.4f}f..{sy1_f:.4f}f){comma}  // column {i+1} ({labels[i]})")
print(f"    )")
print(f"\n    const val ANSWER_CELL_W_FRAC = {ANSWER_CELL_W_FRAC:.4f}f")
print(f"    const val ANSWER_CELL_H_FRAC = {ANSWER_CELL_H_FRAC:.4f}f")
print(f"\n    val answerChoiceXFracs = floatArrayOf("
      f"{', '.join(f'{v:.4f}f' for v in ANSWER_CHOICE_X_FRACS)})")
if ANSWER_ROW_FIRST_Y_FRAC is not None:
    print(f"\n    const val ANSWER_ROW_FIRST_Y_FRAC  = {ANSWER_ROW_FIRST_Y_FRAC:.4f}f")
    print(f"    const val ANSWER_ROW_STRIDE_FRAC   = {ANSWER_ROW_STRIDE_FRAC:.4f}f")
    print(f"    const val ANSWER_SUBGROUP_GAP_FRAC = {ANSWER_SUBGROUP_GAP_FRAC:.4f}f")
print()
print("  // Internal row/choice layout (verify via col*_subwarp_grid.png):")
print(f"  //   MEAN_STRIDE in 1500-warp  = {MEAN_STRIDE:.2f} px")
print(f"  //   SUBGROUP_GAP in 1500-warp = {SUBGROUP_GAP:.2f} px")
print(f"  //   ROW_Y_1500[0]  = {ROW_Y_1500[0]:.1f}  ROW_Y_1500[19] = {ROW_Y_1500[19]:.1f}")
print("=" * 70)
print(f"\nDone. Check debug PNGs in {OUTDIR}")