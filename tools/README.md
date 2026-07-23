# tools/

Python and Kotlin scripts for scanner calibration and validation. Run them from the repo root.

## Requirements

```
pip install opencv-python numpy scipy
```

Most scripts read `reference/sheet-1.png` (the 300-DPI render of `Catc__answer_sheetV4.pdf`).
Regenerate it with: `pdftoppm -r 300 reference/Catc__answer_sheetV4.pdf reference/sheet -png`.

---

## Calibration toolchain (`tools/*.py`)

These regenerate the constants used in production. Re-run only if the printed answer sheet
changes, or when re-tightening marker ROIs from new captures.

| Script | Produces / verifies | Feeds |
|---|---|---|
| `calibrate.py` | Answer-zone calibration: warps to 2400x1500, finds column x-bounds, row centers, choice pitch. Prints a Kotlin constant block. | `SheetBlueprint.kt` answer-grid constants |
| `detect_id_box.py` | Info-zone STUDENT ID + TEST SET box detection, second sub-warp, cell-grid measurement. `python tools/detect_id_box.py reference/sheet-1.png` | `SheetBlueprint.kt` `ID_BOX_*` / `TESTSET_*` |
| `verify_subwarp_overlay.py` | Replays the two-level info pipeline and draws `idGrid`/`testSet` rects on each sub-canvas to visually verify the constants. Imports from `detect_id_box.py`. | (verification only) |
| `calibrate_phase2_rois.py` | Phase-2 marker ROI tightening from a capture folder; prints recommended per-corner ROI fractions. Template for calibrating the open six-marker area band. | `MarkerDetector` Phase-2 ROIs |
| `annotate_phase1_markers.py` | Manual ground-truth marker annotation (info zone). Referenced by name in `MarkerDetector.kt`. Produced `MARKER_ROI_FRAC_PHASE1` from hand-annotated captures. | `MarkerDetector.MARKER_ROI_FRAC_PHASE1` |
| `annotate_phase2_markers.py` | Same, answer zone. Referenced in `InfoZoneProcessor.kt`. | Phase-2 marker ROIs |
| `CalibrateBlueprintKt.kt` | Standalone JVM/Kotlin port of the answer-zone calibration (OpenCV Java). Alternative to `calibrate.py`; run instructions in its header. | `SheetBlueprint.kt` |

---

## Validation harnesses (`tools/validation/*.py`)

Regression and validation harnesses for scanner work that is still open. These read capture
sets that must be supplied separately; fix the hardcoded input paths in each script's header
before running.

| Script | Open work it supports | Input |
|---|---|---|
| `test_six_label.py` | Single-capture 6-marker labeling geometry (`MarkerDetector.detectSix`/`labelSix`). Pure geometry, runs with no external input. | none |
| `subj_topmed.py` | Subject-box top-anchored + median-height curve rectify (`CurveRectifier.rectifyGrayTopAnchored`). | capture set (path hardcoded in script) |
| `subj_e2e.py` | End-to-end subject read (gap-edge localization + read rects) on rectified strips. | strip set (path hardcoded in script) |
| `newsub_e2e.py`, `newsub_e2e2.py` | Newest subject regression set (3/52 bad-warp cases still open). | capture set (path hardcoded in script) |
| `cell_center_error.py` | Per-cell alignment regression metric for the per-column curve-rectify refinement. | misaligned/blurry capture sets (paths hardcoded in script) |
| `blur_metrics.py` | Content blur-gate floor (`ANSWER_BLUR_VARIANCE_MIN`). Gate is now enabled (=300); kept for re-calibration. | blurry + sharp capture sets (paths hardcoded in script) |
| `coons_info_proto.py` | Coons curve-rectify prototype for the ID/TEST-SET boxes (`CurveRectifier.rectifyGray`); info-box dewarp still being hardened. | clean info warps (path hardcoded in script) |

---

## Note on hardcoded paths

`calibrate.py` and everything in `validation/` were authored against absolute input/output
paths. Adjust the paths at the top of each file before running in a fresh checkout.
