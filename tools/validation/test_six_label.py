"""
Standalone validation of the single-capture 6-marker LABELING geometry ported from
MarkerDetector.detectSix/labelSix (bestCornerQuad + winding). It feeds the known
reference marker centres through the algorithm at all 4 in-plane rotations (and a
tilt/perspective jitter) and asserts the labels come back as the correct physical
markers — i.e. orientation-agnostic labeling actually works.

Pure geometry test (no OpenCV): the candidate detection is calibrated on-device;
this isolates the novel algorithm.
"""
import math
import itertools

# Reference sheet marker centres @300 DPI (from calibrate.py), role-keyed.
REF = {
    "TL": (268.0, 838.0),   "TM": (1188.0, 838.0),  "TR": (3712.0, 838.0),
    "BL": (268.0, 2434.0),  "BM": (1188.0, 2434.0), "BR": (3712.0, 2434.0),
}
MARKER_RECT_ASPECT = 3444.0 / 1596.0
SIX_ASPECT_LOG_ERR = math.log(1.5)
MAX_OPPOSITE_SIDE_RATIO = 1.75
MAX_PERSPECTIVE_AREA_RATIO = 3.0
MID_PERP_DIST_FRAC = 0.18
MID_T_TOL = 0.18


def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def order_convex(pts):
    cx = sum(p[0] for p in pts) / 4.0
    cy = sum(p[1] for p in pts) / 4.0
    ordered = sorted(pts, key=lambda p: math.atan2(p[1] - cy, p[0] - cx))
    for i in range(4):
        a, b, c = ordered[i], ordered[(i + 1) % 4], ordered[(i + 2) % 4]
        cross = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
        if abs(cross) < 1e-3:
            return None
    return ordered


def best_corner_quad(cands):
    best, best_err = None, float("inf")
    for combo in itertools.combinations(cands, 4):
        quad = order_convex([c[0] for c in combo])
        if quad is None:
            continue
        areas = [c[1] for c in combo]
        if max(areas) / min(areas) > MAX_PERSPECTIVE_AREA_RATIO:
            continue
        s = [dist(quad[i], quad[(i + 1) % 4]) for i in range(4)]
        if any(v < 1.0 for v in s):
            continue
        pairA = (s[0] + s[2]) / 2.0
        pairB = (s[1] + s[3]) / 2.0
        long_s, short_s = max(pairA, pairB), min(pairA, pairB)
        if max(s[0], s[2]) / min(s[0], s[2]) > MAX_OPPOSITE_SIDE_RATIO:
            continue
        if max(s[1], s[3]) / min(s[1], s[3]) > MAX_OPPOSITE_SIDE_RATIO:
            continue
        aspect = long_s / short_s
        ae = abs(math.log(aspect / MARKER_RECT_ASPECT))
        if ae > SIX_ASPECT_LOG_ERR:
            continue
        side_err = (max(s[0], s[2]) / min(s[0], s[2]) - 1.0) + (max(s[1], s[3]) / min(s[1], s[3]) - 1.0)
        err = ae + side_err
        if err < best_err:
            best_err, best = err, quad
    return best


def find_mid(p0, p1, short_len, cands, corners):
    ex, ey = p1[0] - p0[0], p1[1] - p0[1]
    len2 = ex * ex + ey * ey
    if len2 < 1.0:
        return None
    max_perp = MID_PERP_DIST_FRAC * short_len
    best, best_err = None, float("inf")
    for c in cands:
        pt = c[0]
        if any(dist(k, pt) < short_len * 0.25 for k in corners):
            continue
        t = ((pt[0] - p0[0]) * ex + (pt[1] - p0[1]) * ey) / len2
        if t <= 0.05 or t >= 0.95:
            continue
        projx, projy = p0[0] + t * ex, p0[1] + t * ey
        perp = math.hypot(pt[0] - projx, pt[1] - projy)
        if perp > max_perp:
            continue
        terr = min(abs(t - 0.267), abs(t - 0.733))
        if terr > MID_T_TOL:
            continue
        err = terr + perp / max_perp
        if err < best_err:
            best_err, best = err, (pt, t)
    return best


def shoelace(p):
    s = 0.0
    for i in range(4):
        a, b = p[i], p[(i + 1) % 4]
        s += a[0] * b[1] - b[0] * a[1]
    return s


def label_six(corners, cands):
    s = [dist(corners[i], corners[(i + 1) % 4]) for i in range(4)]
    long_is_e0e2 = (s[0] + s[2]) >= (s[1] + s[3])
    short_len = (s[1] + s[3]) / 2.0 if long_is_e0e2 else (s[0] + s[2]) / 2.0
    ea, ec = (0, 2) if long_is_e0e2 else (1, 3)
    a0, a1 = corners[ea], corners[(ea + 1) % 4]
    c0, c1 = corners[ec], corners[(ec + 1) % 4]
    midA = find_mid(a0, a1, short_len, cands, corners)
    midC = find_mid(c0, c1, short_len, cands, corners)
    if midA is None or midC is None:
        return None
    infoA, ansA = (a0, a1) if midA[1] < 0.5 else (a1, a0)
    infoC, ansC = (c0, c1) if midC[1] < 0.5 else (c1, c0)
    if shoelace([infoA, ansA, ansC, infoC]) > 0:
        return [infoA, midA[0], ansA, infoC, midC[0], ansC]
    return [infoC, midC[0], ansC, infoA, midA[0], ansA]


def select_six(points):
    cands = [(p, 84.0 * 84.0) for p in points]  # equal-area markers
    corners = best_corner_quad(cands)
    if corners is None:
        return None
    return label_six(corners, cands)


def rotate(p, deg, w, h):
    x, y = p
    if deg == 0:
        return (x, y)
    if deg == 90:
        return (h - 1 - y, x)
    if deg == 180:
        return (w - 1 - x, h - 1 - y)
    if deg == 270:
        return (y, w - 1 - x)


def near(a, b, tol=1.5):
    return dist(a, b) < tol


ROLES = ["TL", "TM", "TR", "BL", "BM", "BR"]
W, H = 3900, 2550


def run(label, transform):
    pts = {r: transform(REF[r]) for r in ROLES}
    out = select_six([pts[r] for r in ROLES])
    if out is None:
        print(f"  [{label}] FAIL — no grid")
        return False
    ok = all(near(out[i], pts[ROLES[i]]) for i in range(6))
    status = "ok" if ok else "MISLABELED"
    print(f"  [{label}] {status}")
    if not ok:
        for i, r in enumerate(ROLES):
            print(f"      {r}: got {tuple(round(v) for v in out[i])} want {tuple(round(v) for v in pts[r])}")
    return ok


print("Orientation-agnostic 6-marker labeling:")
all_ok = True
for deg in (0, 90, 180, 270):
    all_ok &= run(f"rot {deg}", lambda p, d=deg: rotate(p, d, W, H))

# Perspective/tilt jitter on top of each rotation (shear + scale of corners).
def jitter(p):
    x, y = p
    return (x + 0.04 * y, y - 0.03 * x + 0.0000015 * x * y)

for deg in (0, 90, 180, 270):
    all_ok &= run(f"rot {deg}+tilt", lambda p, d=deg: jitter(rotate(p, d, W, H)))

# Distractor squares (ID-grid cells, smaller) must not steal a marker slot.
def with_distractors(transform):
    base = [transform(REF[r]) for r in ROLES]
    extra = [(600 + 80 * i, 1600) for i in range(4)]  # small fake squares
    cands = [(p, 84.0 * 84.0) for p in base] + [(p, 30.0 * 30.0) for p in extra]
    corners = best_corner_quad(cands)
    if corners is None:
        return None
    return label_six(corners, cands)

print("With distractor squares:")
for deg in (0, 90, 180, 270):
    pts = {r: rotate(REF[r], deg, W, H) for r in ROLES}
    out = with_distractors(lambda p, d=deg: rotate(p, d, W, H))
    ok = out is not None and all(near(out[i], pts[ROLES[i]]) for i in range(6))
    all_ok &= ok
    print(f"  [rot {deg}] {'ok' if ok else 'FAIL'}")

print("\nRESULT:", "ALL PASS" if all_ok else "FAILURES PRESENT")
