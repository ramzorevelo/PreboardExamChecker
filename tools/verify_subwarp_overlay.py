"""
verify_subwarp_overlay.py — replays the new two-level pipeline end-to-end
(main warp -> BoxFinder -> second warp -> SheetBlueprint cell rects) and draws
the idGrid / testSet cell rects on each sub-canvas, mirroring
InfoZoneProcessor + BubbleReader.normToRect/debugOverlay in Kotlin, so the
calibrated SheetBlueprint constants can be visually verified before building.
"""
import sys
import cv2
import numpy as np

sys.path.insert(0, "tools")
from detect_id_box import find_markers, find_box_corners, subwarp, INFO_W, INFO_H

# ── Mirrors of the final SheetBlueprint constants ────────────────────────────
ID_BOX_W, ID_BOX_H = 340, 600
ID_CELL_W_FRAC, ID_CELL_H_FRAC = 0.0735, 0.0667
ID_COL_CENTERS = [81, 125, 168, 212, 256, 300]
ID_ROW_CENTERS = [138, 186, 234, 282, 330, 378, 425, 472, 520, 568]

TESTSET_BOX_W, TESTSET_BOX_H = 360, 140
TESTSET_CELL_W_FRAC, TESTSET_CELL_H_FRAC = 0.0556, 0.2857
TESTSET_CENTERS = {'A': (178, 100), 'B': (273, 100)}


def norm_to_rect(cx_px, cy_px, canvas_w, canvas_h, w_frac, h_frac):
    pw = max(1, int(w_frac * canvas_w))
    ph = max(1, int(h_frac * canvas_h))
    px = int(cx_px - pw / 2)
    py = int(cy_px - ph / 2)
    return px, py, pw, ph


def draw_overlay(sub_gray, rects, name):
    bgr = cv2.cvtColor(sub_gray, cv2.COLOR_GRAY2BGR)
    for (x, y, w, h) in rects:
        cv2.rectangle(bgr, (x, y), (x + w, y + h), (0, 255, 0), 1)
        # 1px inset interior (matches BubbleReader.readFill)
        cv2.rectangle(bgr, (x + 1, y + 1), (x + w - 1, y + h - 1), (255, 0, 0), 1)
    cv2.imwrite(f"reference/{name}_blueprint_overlay.png", bgr)
    print(f"Saved reference/{name}_blueprint_overlay.png")


def main():
    input_path = sys.argv[1] if len(sys.argv) > 1 else "reference/sheet-1.png"
    src = cv2.imread(input_path)
    gray = cv2.cvtColor(src, cv2.COLOR_BGR2GRAY)
    img_w = gray.shape[1]

    tl, tm, bl, bm = find_markers(gray, img_w)
    src_pts = np.float32([tl['center'], tm['center'], bl['center'], bm['center']])
    dst_pts = np.float32([[0, 0], [INFO_W, 0], [0, INFO_H], [INFO_W, INFO_H]])
    H = cv2.getPerspectiveTransform(src_pts, dst_pts)
    warped = cv2.warpPerspective(gray, H, (INFO_W, INFO_H))

    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    warped_enh = clahe.apply(warped)

    # y-range matches InfoZoneProcessor.ID_BOX_Y_RANGE (0.18..0.80) so this replay
    # detects the same (taller, header-inclusive) box as the app does.
    id_corners = find_box_corners(warped_enh, (0.15, 0.85, 0.18, 0.80), tall=True,
                                  area_frac_range=(0.05, 0.75), label="ID-BOX")
    ts_corners = find_box_corners(warped_enh, (0.05, 0.95, 0.03, 0.20), tall=False,
                                  area_frac_range=(0.05, 0.85), label="TESTSET-BOX")

    if id_corners is not None:
        id_sub = subwarp(warped, id_corners, ID_BOX_W, ID_BOX_H)
        rects = []
        for cx in ID_COL_CENTERS:
            for cy in ID_ROW_CENTERS:
                rects.append(norm_to_rect(cx, cy, ID_BOX_W, ID_BOX_H, ID_CELL_W_FRAC, ID_CELL_H_FRAC))
        draw_overlay(id_sub, rects, "id_box")

    if ts_corners is not None:
        ts_sub = subwarp(warped, ts_corners, TESTSET_BOX_W, TESTSET_BOX_H)
        rects = []
        for (cx, cy) in TESTSET_CENTERS.values():
            rects.append(norm_to_rect(cx, cy, TESTSET_BOX_W, TESTSET_BOX_H,
                                       TESTSET_CELL_W_FRAC, TESTSET_CELL_H_FRAC))
        draw_overlay(ts_sub, rects, "testset_box")


if __name__ == "__main__":
    main()
