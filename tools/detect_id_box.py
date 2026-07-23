"""
detect_id_box.py — prototype the dynamic STUDENT ID box + TEST SET box detection
and secondary sub-warp, then measure cell grid positions inside each sub-warp.

Run: python tools/detect_id_box.py reference/sheet-1.png
"""
import sys
import cv2
import numpy as np

INFO_W, INFO_H = 800, 1300


def find_markers(gray, img_w):
    clahe = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(32, 32))
    enhanced = clahe.apply(gray)
    _, binary = cv2.threshold(enhanced, 0, 255, cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)

    expected_side = int(img_w * 0.0215)
    expected_area = expected_side ** 2
    min_area, max_area = int(expected_area * 0.5), int(expected_area * 3.0)

    contours, _ = cv2.findContours(binary.copy(), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    cands = []
    for c in contours:
        x, y, w, h = cv2.boundingRect(c)
        bbox = w * h
        if not (min_area <= bbox <= max_area):
            continue
        if w / h < 0.5 or w / h > 2.0:
            continue
        ca = cv2.contourArea(c)
        if ca / bbox < 0.70:
            continue
        cands.append({'center': (x + w / 2.0, y + h / 2.0), 'area': ca})

    sorted_by_x = sorted(cands, key=lambda c: c['center'][0])
    left4 = sorted_by_x[:4]
    left4_by_y = sorted(left4, key=lambda c: c['center'][1])
    top2 = sorted(left4_by_y[:2], key=lambda c: c['center'][0])
    bot2 = sorted(left4_by_y[2:], key=lambda c: c['center'][0])
    return top2[0], top2[1], bot2[0], bot2[1]


def find_box_corners(warped_gray, region_frac, tall, area_frac_range, label):
    """
    region_frac: (x0, x1, y0, y1) as fractions of warp dimensions
    tall: True => filter for height > width; False => width > height
    Returns (corners as 4x2 float32 array ordered TL,TR,BL,BR) or None
    """
    h, w = warped_gray.shape[:2]
    x0, x1, y0, y1 = (int(region_frac[0] * w), int(region_frac[1] * w),
                      int(region_frac[2] * h), int(region_frac[3] * h))
    roi = warped_gray[y0:y1, x0:x1]

    binary = cv2.adaptiveThreshold(roi, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                                   cv2.THRESH_BINARY_INV, 21, 8.0)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3))
    closed = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, kernel, iterations=2)

    contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    roi_area = (x1 - x0) * (y1 - y0)
    min_area, max_area = roi_area * 0.05, roi_area * 0.75

    print(f"\n[{label}] ROI=({x0},{y0})-({x1},{y1})  size={x1-x0}x{y1-y0}  contours={len(contours)}")
    best = None
    best_area = 0
    for c in contours:
        area = cv2.contourArea(c)
        if not (min_area <= area <= max_area):
            continue
        x, y, bw, bh = cv2.boundingRect(c)
        aspect = bw / bh if bh else 0
        ok_aspect = (aspect < 0.95) if tall else (aspect > 1.05)
        peri = cv2.arcLength(c, True)
        approx = cv2.approxPolyDP(c, 0.02 * peri, True)
        print(f"   contour: bbox=({x},{y},{bw},{bh}) area={area:.0f} "
              f"({area/roi_area*100:.1f}% roi) aspect={aspect:.2f} corners={len(approx)} "
              f"{'OK' if ok_aspect else 'reject-aspect'}")
        if not ok_aspect:
            continue
        if len(approx) != 4:
            continue
        if area > best_area:
            best_area = area
            best = approx.reshape(4, 2).astype(np.float32)

    if best is None:
        print(f"   -> NO 4-corner box found")
        return None

    # translate to full warped-canvas coords
    pts = best + np.array([x0, y0], dtype=np.float32)

    # order as TL, TR, BL, BR
    s = pts.sum(axis=1)
    diff = pts[:, 0] - pts[:, 1]
    tl = pts[np.argmin(s)]
    br = pts[np.argmax(s)]
    tr = pts[np.argmax(diff)]
    bl = pts[np.argmin(diff)]
    ordered = np.array([tl, tr, bl, br], dtype=np.float32)
    print(f"   -> corners TL={tl} TR={tr} BL={bl} BR={br}")
    return ordered


def subwarp(gray, corners, out_w, out_h):
    dst = np.float32([[0, 0], [out_w, 0], [0, out_h], [out_w, out_h]])
    H = cv2.getPerspectiveTransform(corners, dst)
    return cv2.warpPerspective(gray, H, (out_w, out_h))


def measure_blob_grid(sub, n_cols, n_rows, label, roi_frac=(0.0, 1.0, 0.0, 1.0),
                       area_range=(50, 5000), aspect_range=(0.5, 2.0), max_dim=60):
    """
    Detects each bracket cell as an individual connected-component blob,
    then clusters their centers into an n_cols x n_rows grid (sorted by
    row-cluster of y, then by x within each row).
    roi_frac restricts the search area (to exclude headers/labels) as
    (x0,x1,y0,y1) fractions of `sub` dimensions.
    Returns (col_centers, row_centers, list of (cx,cy) per cell in [row][col] order).
    """
    h, w = sub.shape[:2]
    x0, x1 = int(w * roi_frac[0]), int(w * roi_frac[1])
    y0, y1 = int(h * roi_frac[2]), int(h * roi_frac[3])
    roi = sub[y0:y1, x0:x1]

    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    enh = clahe.apply(roi)
    binary = cv2.adaptiveThreshold(enh, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                                   cv2.THRESH_BINARY_INV, 21, 8.0)
    # Dilate aggressively to merge each cell's bracket fragments + printed digit
    # glyph into a single connected blob per cell (brackets are open corner shapes).
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    closed = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, kernel, iterations=2)

    contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    blobs = []
    for c in contours:
        area = cv2.contourArea(c)
        if not (area_range[0] <= area <= area_range[1]):
            continue
        x, y, bw, bh = cv2.boundingRect(c)
        if bw > max_dim or bh > max_dim:
            continue
        aspect = bw / bh if bh else 0
        if not (aspect_range[0] <= aspect <= aspect_range[1]):
            continue
        blobs.append((x + bw / 2.0 + x0, y + bh / 2.0 + y0, bw, bh))

    print(f"\n[{label}] blob candidates: {len(blobs)} (need {n_cols * n_rows})")
    if len(blobs) != n_cols * n_rows:
        for b in sorted(blobs, key=lambda b: (b[1], b[0])):
            print(f"    blob center=({b[0]:.1f},{b[1]:.1f}) size={b[2]}x{b[3]} area={b[2]*b[3]}")
    if len(blobs) < n_cols * n_rows:
        return None, None, None

    # Cluster by y into n_rows groups
    blobs_sorted_y = sorted(blobs, key=lambda b: b[1])
    ys = np.array([b[1] for b in blobs_sorted_y])
    # k-means style 1D clustering: split into n_rows contiguous groups by gaps
    row_groups = _cluster_1d(blobs_sorted_y, key=lambda b: b[1], n_groups=n_rows)
    row_centers = [int(round(np.mean([b[1] for b in g]))) for g in row_groups]

    col_centers_per_row = []
    grid = []
    for g in row_groups:
        g_sorted = sorted(g, key=lambda b: b[0])
        col_centers_per_row.append([b[0] for b in g_sorted])
        grid.append([(b[0], b[1]) for b in g_sorted])

    # Average column centers across all rows
    col_centers = [int(round(np.mean([row[c] for row in col_centers_per_row])))
                   for c in range(n_cols)]

    print(f"[{label}] row_centers ({len(row_centers)}): {row_centers}")
    print(f"[{label}] col_centers ({len(col_centers)}): {col_centers}")
    return col_centers, row_centers, grid


def _cluster_1d(items, key, n_groups):
    """Split `items` into n_groups contiguous clusters by sorting on key and
    cutting at the n_groups-1 largest gaps."""
    s = sorted(items, key=key)
    vals = [key(x) for x in s]
    gaps = [(vals[i + 1] - vals[i], i) for i in range(len(vals) - 1)]
    gaps.sort(reverse=True)
    cut_idxs = sorted(i for _, i in gaps[:n_groups - 1])
    groups = []
    start = 0
    for ci in cut_idxs:
        groups.append(s[start:ci + 1])
        start = ci + 1
    groups.append(s[start:])
    return groups


def main():
    input_path = sys.argv[1] if len(sys.argv) > 1 else "reference/sheet-1.png"
    src = cv2.imread(input_path)
    if src is None:
        print(f"ERROR: cannot load {input_path}")
        return
    gray = cv2.cvtColor(src, cv2.COLOR_BGR2GRAY)
    img_w = gray.shape[1]

    tl, tm, bl, bm = find_markers(gray, img_w)
    src_pts = np.float32([tl['center'], tm['center'], bl['center'], bm['center']])
    dst_pts = np.float32([[0, 0], [INFO_W, 0], [0, INFO_H], [INFO_W, INFO_H]])
    H = cv2.getPerspectiveTransform(src_pts, dst_pts)
    warped = cv2.warpPerspective(gray, H, (INFO_W, INFO_H))
    print(f"Warped info zone: {warped.shape[1]}x{warped.shape[0]}")

    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    warped_enh = clahe.apply(warped)

    # ── ID box: y in [18%,80%], x in [15%,85%], tall rectangle ──────────────
    # Matches InfoZoneProcessor.ID_BOX_Y_RANGE (0.18..0.80) — the current
    # BoxFinder now detects a taller box that includes the "STUDENT ID" header.
    id_corners = find_box_corners(warped_enh, (0.15, 0.85, 0.18, 0.80), tall=True,
                                  area_frac_range=(0.05, 0.75), label="ID-BOX")

    # ── Test set box: roughly y in [8%,20%], x in [25%,75%] ──────────────────
    ts_corners = find_box_corners(warped_enh, (0.05, 0.95, 0.03, 0.20), tall=False,
                                  area_frac_range=(0.05, 0.85), label="TESTSET-BOX")

    annotated = cv2.cvtColor(warped, cv2.COLOR_GRAY2BGR)

    if id_corners is not None:
        cv2.polylines(annotated, [id_corners.astype(np.int32)], True, (0, 255, 0), 2)
        ID_W, ID_H = 340, 600
        id_sub = subwarp(warped, id_corners, ID_W, ID_H)
        cv2.imwrite("reference/id_box_subwarp.png", id_sub)
        # Exclude the "STUDENT ID #" header row from blob search. The current
        # BoxFinder detects a taller box that includes this header, so it now
        # occupies more of the sub-warp (~18%, vs ~12% before) — a stray glyph
        # fragment at y≈84 (14% of 600) otherwise sneaks past a 12% cutoff and
        # corrupts the row clustering.
        cc, rc, grid = measure_blob_grid(id_sub, n_cols=6, n_rows=10, label="ID-GRID",
                                          roi_frac=(0.0, 1.0, 0.18, 1.0),
                                          area_range=(150, 5000), aspect_range=(0.3, 3.0))
        sub_ann = cv2.cvtColor(id_sub, cv2.COLOR_GRAY2BGR)
        if grid:
            for row in grid:
                for (x, y) in row:
                    cv2.drawMarker(sub_ann, (int(x), int(y)), (0, 0, 255),
                                   cv2.MARKER_CROSS, 10, 1)
        cv2.imwrite("reference/id_box_subwarp_annotated.png", sub_ann)
        print(f"\nID sub-warp canonical size: {ID_W}x{ID_H}")
        if cc and rc:
            print(f"ID-GRID fractions of {ID_W}x{ID_H}:")
            print(f"  col fracs: {[round(c/ID_W,4) for c in cc]}")
            print(f"  row fracs: {[round(r/ID_H,4) for r in rc]}")

    if ts_corners is not None:
        cv2.polylines(annotated, [ts_corners.astype(np.int32)], True, (0, 0, 255), 2)
        TS_W, TS_H = 360, 140
        ts_sub = subwarp(warped, ts_corners, TS_W, TS_H)
        cv2.imwrite("reference/testset_box_subwarp.png", ts_sub)
        # A/B brackets sit in the right ~60% of the box, vertically centered
        cc, rc, grid = measure_blob_grid(ts_sub, n_cols=2, n_rows=1, label="TESTSET-GRID",
                                          roi_frac=(0.35, 1.0, 0.0, 1.0),
                                          area_range=(50, 3000), aspect_range=(0.4, 2.5))
        sub_ann = cv2.cvtColor(ts_sub, cv2.COLOR_GRAY2BGR)
        if grid:
            for row in grid:
                for (x, y) in row:
                    cv2.drawMarker(sub_ann, (int(x), int(y)), (0, 0, 255),
                                   cv2.MARKER_CROSS, 10, 1)
        cv2.imwrite("reference/testset_box_subwarp_annotated.png", sub_ann)
        print(f"\nTestset sub-warp canonical size: {TS_W}x{TS_H}")
        if cc and rc:
            print(f"TESTSET-GRID fractions of {TS_W}x{TS_H}:")
            print(f"  col fracs: {[round(c/TS_W,4) for c in cc]}")
            print(f"  row fracs: {[round(r/TS_H,4) for r in rc]}")

    cv2.imwrite("reference/box_detection_overlay.png", annotated)
    print("\nSaved reference/box_detection_overlay.png")


if __name__ == "__main__":
    main()
