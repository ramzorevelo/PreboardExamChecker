"""
Prototype FULL-COONS curve rectify on the curved STUDENT ID and TEST SET boxes,
straight from the clean info warp (info_warped_*). Mirrors the planned Kotlin
curveRectifyBox: detect the box contour, trace all 4 silhouette edges, smooth each,
full-Coons remap. The dark header bar gives a strong top anchor.
"""
import sys
import cv2
import numpy as np

PAD = 6


def binarize(gray):
    # box borders + header bars are darker than paper; CLAHE then Otsu-inv
    clahe = cv2.createCLAHE(2.0, (8, 8))
    g = clahe.apply(gray)
    _, b = cv2.threshold(g, 0, 255, cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)
    b = cv2.morphologyEx(b, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_RECT, (7, 7)))
    return b


def find_boxes(binimg):
    """Return contours of plausible boxes: ID (tall, mid), SET (wide, top)."""
    H, W = binimg.shape
    cnts, _ = cv2.findContours(binimg, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    out = []
    for c in cnts:
        x, y, w, h = cv2.boundingRect(c)
        area = w * h
        if area < 0.04 * W * H:
            continue
        if w < 0.35 * W:
            continue
        out.append((x, y, w, h, c))
    out.sort(key=lambda t: t[1])  # top to bottom
    return out


def smooth(raw):
    n = len(raw); a = raw.astype(np.float64).copy(); good = a >= 0
    if good.sum() < 2:
        return np.zeros(n)
    idx = np.arange(n)
    a = np.interp(idx, idx[good], a[good])
    a = cv2.medianBlur(a.astype(np.float32).reshape(-1, 1), 5).ravel().astype(np.float64)
    k = max(3, int(0.04 * n) | 1)
    return np.convolve(a, np.ones(k) / k, mode="same")


def trace(mask):
    h, w = mask.shape
    L = np.full(h, -1.0); R = np.full(h, -1.0)
    for y in range(h):
        xs = np.where(mask[y] != 0)[0]
        if xs.size: L[y], R[y] = xs[0], xs[-1]
    T = np.full(w, -1.0); B = np.full(w, -1.0)
    for x in range(w):
        ys = np.where(mask[:, x] != 0)[0]
        if ys.size: T[x], B[x] = ys[0], ys[-1]
    return smooth(L), smooth(R), smooth(T), smooth(B)


def coons(src, bx0, by0, L, R, T, B, outW, outH):
    cropH, cropW = len(L), len(T)
    u = np.linspace(0, 1, outW); v = np.linspace(0, 1, outH)
    Lx = np.interp(v, np.linspace(0, 1, cropH), L); Ly = v * (cropH - 1)
    Rx = np.interp(v, np.linspace(0, 1, cropH), R); Ry = v * (cropH - 1)
    Ty = np.interp(u, np.linspace(0, 1, cropW), T); Tx = u * (cropW - 1)
    By = np.interp(u, np.linspace(0, 1, cropW), B); Bx = u * (cropW - 1)
    P00 = (Lx[0], Ly[0]); P01 = (Lx[-1], Ly[-1]); P10 = (Rx[0], Ry[0]); P11 = (Rx[-1], Ry[-1])
    U, V = np.meshgrid(u, v)
    Sx = ((1 - U) * Lx[:, None] + U * Rx[:, None] + (1 - V) * Tx[None, :] + V * Bx[None, :]
          - ((1 - U) * (1 - V) * P00[0] + U * (1 - V) * P10[0] + (1 - U) * V * P01[0] + U * V * P11[0]))
    Sy = ((1 - U) * Ly[:, None] + U * Ry[:, None] + (1 - V) * Ty[None, :] + V * By[None, :]
          - ((1 - U) * (1 - V) * P00[1] + U * (1 - V) * P10[1] + (1 - U) * V * P01[1] + U * V * P11[1]))
    return cv2.remap(src, (bx0 + Sx).astype(np.float32), (by0 + Sy).astype(np.float32),
                     cv2.INTER_NEAREST, borderValue=255)


def rectify(gray, binimg, c, outW, outH, tag):
    x, y, w, h = cv2.boundingRect(c)
    bx0, by0 = max(0, x - PAD), max(0, y - PAD)
    bx1, by1 = min(gray.shape[1], x + w + PAD), min(gray.shape[0], y + h + PAD)
    mask = np.zeros((by1 - by0, bx1 - bx0), np.uint8)
    cv2.drawContours(mask, [c], -1, 255, -1, offset=(-bx0, -by0))
    L, R, T, B = trace(mask)
    def bow(a):
        i = np.arange(len(a)); return float(np.max(np.abs(a - np.polyval(np.polyfit(i, a, 1), i))))
    print(f"  {tag} bbox=({x},{y},{w},{h}) bow top={bow(T):.1f} bot={bow(B):.1f} left={bow(L):.1f} right={bow(R):.1f}")
    out = coons(gray, bx0, by0, L, R, T, B, outW, outH)
    cv2.imwrite(rf"C:\tmp\cal\coons_{tag}_before.png",
                cv2.resize(gray[by0:by1, bx0:bx1], (outW, outH), interpolation=cv2.INTER_NEAREST))
    cv2.imwrite(rf"C:\tmp\cal\coons_{tag}_after.png", out)


def main():
    path = sys.argv[1]
    gray = cv2.imread(path, 0)
    binimg = binarize(gray)
    boxes = find_boxes(binimg)
    print(f"{path}: {len(boxes)} boxes (y-sorted): {[(b[0],b[1],b[2],b[3]) for b in boxes]}")
    # SET = top wide box, ID = the tall middle box (largest area), IMPORTANT = bottom
    if not boxes:
        return
    set_box = boxes[0]
    id_box = max(boxes, key=lambda b: b[2] * b[3])
    rectify(gray, binimg, set_box[4], 360, 140, "set")
    rectify(gray, binimg, id_box[4], 340, 600, "id")


if __name__ == "__main__":
    main()
