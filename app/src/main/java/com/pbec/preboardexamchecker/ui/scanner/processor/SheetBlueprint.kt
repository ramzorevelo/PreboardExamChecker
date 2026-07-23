package com.pbec.preboardexamchecker.ui.scanner.processor

/**
 * Normalized cell positions for both scan zones.
 * All values are fractions of the canonical warp dimensions:
 *   Info zone:   INFO_W × INFO_H  = 800 × 1300 px
 *   Answer zone: ANS_W  × ANS_H   = 2400 × 1400 px
 *
 * Calibrated against: Catc_answer_sheetV4.pdf (300 DPI, 3900×2550 px)
 * V4 warp diff vs V1: max=0, mean=0 — layout is pixel-identical.
 *
 * Marker pixel coordinates in reference/sheet-1.png (3900×2550):
 *   TL=(268,838)  TM=(1188,838)  TR=(3712,838)
 *   BL=(268,2434) BM=(1188,2434) BR=(3712,2434)
 *   Marker size: 84×84 px (solidity 0.98)
 *
 * Phase 1 uses: TL+TM+BL+BM  (info zone)
 * Phase 2 uses: TM+TR+BM+BR  (answer zone)
 *
 * Info-zone cells use a two-level coordinate system. Origins hardcoded against the
 * 800×1300 canvas drift ~1 cell across captures, so InfoZoneProcessor locates the STUDENT
 * ID and TEST QUESTION SET boxes (BoxFinder) and applies a second warpPerspective into a
 * fixed sub-canvas each:
 *   STUDENT ID box        → ID_BOX_W × ID_BOX_H        = 340 × 600 px
 *   TEST QUESTION SET box → TESTSET_BOX_W × TESTSET_BOX_H = 360 × 140 px
 * idGrid / testSetA / testSetB are fractions of these sub-canvases (re-measured from
 * reference/sheet-1.png, 2026-06-07), so cell positions never drift between captures.
 */
object SheetBlueprint {

    // ── Canonical warp dimensions ─────────────────────────────────────────
    const val INFO_W = 800
    const val INFO_H = 1300
    const val ANS_W  = 2400
    const val ANS_H  = 1400

    // Legacy square-cell size: answer zone's landscape canvas makes the old normToRect
    // give roughly square cells.
    const val ANS_CELL_SIZE = 0.021f

    // ═════════════════════════════════════════════════════════════════════
    // INFO ZONE — two-level sub-canvases
    // ═════════════════════════════════════════════════════════════════════

    // ── STUDENT ID box sub-canvas ─────────────────────────────────────────
    const val ID_BOX_W = 340
    const val ID_BOX_H = 600

    // STUDENT ID box bounding rect in the 800×1300 info canvas (not the sub-canvas), for
    // BoxFinder.findBox's size gate. Paper only foreshortens a box, so an oversized
    // detection is a false contour. ≈409×653 px, from tools/detect_id_box.py (2026-06-08).
    const val ID_BOX_TEMPLATE_W = 409
    const val ID_BOX_TEMPLATE_H = 653

    // Bracket frame ≈23×37 px in the 340×600 sub-canvas; cell rect ≈25×40 px adds margin so
    // BubbleReader's 1px inset clears the border.
    const val ID_CELL_W_FRAC = 0.0735f
    const val ID_CELL_H_FRAC = 0.0667f

    // ── TEST QUESTION SET box sub-canvas ──────────────────────────────────
    const val TESTSET_BOX_W = 360
    const val TESTSET_BOX_H = 140

    // TEST QUESTION SET box bounding rect in the 800×1300 info canvas (see
    // ID_BOX_TEMPLATE_W for why). ≈602×143 px, from tools/detect_id_box.py (2026-06-08).
    const val TESTSET_BOX_TEMPLATE_W = 602
    const val TESTSET_BOX_TEMPLATE_H = 143

    // Bracket frame ≈17×37 px in the 360×140 sub-canvas; cell rect ≈20×40 px.
    const val TESTSET_CELL_W_FRAC = 0.0556f
    const val TESTSET_CELL_H_FRAC = 0.2857f

    // ── Test Set cells — fractions of the 360×140 sub-canvas ──
    // A center (178,100), B center (273,100).
    val testSetA = NormPoint(178f / TESTSET_BOX_W, 100f / TESTSET_BOX_H)
    val testSetB = NormPoint(273f / TESTSET_BOX_W, 100f / TESTSET_BOX_H)

    // ── Student ID grid — fractions of the 340×600 sub-canvas (tools/detect_id_box.py, 2026-06-08) ──
    // Column x-centers: 81,125,168,212,256,300 px   (stride ≈ 43.8 px)
    // Row y-centers:    138,186,234,282,330,378,425,472,520,568 px (stride ≈ 47.8 px)
    private val ID_COL_CENTERS = floatArrayOf(81f, 125f, 168f, 212f, 256f, 300f)
    private val ID_ROW_CENTERS = floatArrayOf(
        138f, 186f, 234f, 282f, 330f, 378f, 425f, 472f, 520f, 568f
    )

    val idGrid: Array<Array<NormPoint>> = Array(6) { col ->
        val cx = ID_COL_CENTERS[col] / ID_BOX_W
        Array(10) { digit ->
            NormPoint(cx, ID_ROW_CENTERS[digit] / ID_BOX_H)
        }
    }

    // ── ID grid comb-fit priors (fractions of the 340×600 sub-canvas) ──
    // Pitch/origin priors for DynamicCellDetector's grid-fit path, derived from the
    // measured centers above so the comb fit starts from the same geometry as idGrid.
    val idColXFracs: FloatArray = FloatArray(6) { ID_COL_CENTERS[it] / ID_BOX_W }
    const val ID_COL_FIRST_X_FRAC = 81f / ID_BOX_W
    const val ID_COL_STRIDE_FRAC  = (300f - 81f) / 5f / ID_BOX_W   // ≈ 0.1288
    const val ID_ROW_FIRST_Y_FRAC = 138f / ID_BOX_H
    const val ID_ROW_STRIDE_FRAC  = (568f - 138f) / 9f / ID_BOX_H  // ≈ 0.0796

    // ── TEST QUESTION SET comb-fit priors (fractions of the 360×140 sub-canvas) ──
    // The 2 SET cells (A, B) for DynamicCellDetector.detectTestSetCells.
    val testSetXFracs: FloatArray = floatArrayOf(testSetA.x, testSetB.x)
    const val TESTSET_FALLBACK_Y_FRAC = 100f / TESTSET_BOX_H

    // ═════════════════════════════════════════════════════════════════════
    // ANSWER ZONE  (2400 × 1400)
    // ═════════════════════════════════════════════════════════════════════

    // ── Subject cells ─────────────────────────────────────────────────────
    // MATH=(852,88)  ESAS=(1347,88)  PROFESSIONAL_EE=(1842,88)
    val subjectMath           = NormPoint(852f  / ANS_W, 88f / ANS_H)
    val subjectEsas           = NormPoint(1347f / ANS_W, 88f / ANS_H)
    val subjectProfessionalEe = NormPoint(1842f / ANS_W, 88f / ANS_H)

    // ── Answer grid ───────────────────────────────────────────────────────
    // 5 blocks of 20 questions each.
    // Choice x-centers per block (A..E, pitch 58 px):
    //   B1: 173,231,289,347,405
    //   B2: 641,699,757,815,873
    //   B3: 1109,1167,1225,1283,1341
    //   B4: 1577,1635,1693,1751,1809
    //   B5: 2045,2103,2161,2219,2277
    //
    // Row y-centers (shared across all blocks):
    //   Sub-group 1 (rows 1–10):  182,235,287,338,390,442,493,546,598,650
    //   Sub-group 2 (rows 11–20): 732,785,837,888,940,992,1043,1096,1148,1200

    internal val blockAnchorX = intArrayOf(173, 641, 1109, 1577, 2045)
    internal val choicePitch  = 58

    internal val rowYCenters = intArrayOf(
        // sub-group 1
        182, 235, 287, 338, 390, 442, 493, 546, 598, 650,
        // sub-group 2
        732, 785, 837, 888, 940, 992, 1043, 1096, 1148, 1200
    )

    /**
     * answerGrid[questionIndex][choiceIndex]
     *   questionIndex: 0..99  (Q1=0, Q100=99)
     *   choiceIndex:   0=A, 1=B, 2=C, 3=D, 4=E
     */
    val answerGrid: Array<Array<NormPoint>> = Array(100) { qIdx ->
        val block    = qIdx / 20
        val rowInBlk = qIdx % 20
        val cy = rowYCenters[rowInBlk].toFloat() / ANS_H
        Array(5) { choiceIdx ->
            val cx = (blockAnchorX[block] + choiceIdx * choicePitch).toFloat() / ANS_W
            NormPoint(cx, cy)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ANSWER ZONE — Phase 5.4 dynamic detection constants
    // ═════════════════════════════════════════════════════════════════════

    // ANSWER_WARP_H is 1500; legacy ANS_H = 1400 kept so the existing answerGrid / subject
    // NormPoints stay in sync with the old warp.
    const val ANSWER_WARP_W = 2400
    const val ANSWER_WARP_H = 1500

    // Per-column sub-warp (crop → resize). tools/calibrate.py, 2026-06-11, V4 PDF at 300 DPI.
    const val ANSWER_COL_WARP_W = 538
    const val ANSWER_COL_WARP_H = 1600

    // Subject strip — y-band on the ANSWER_WARP_H canvas containing the 3 subject bubbles.
    const val SUBJECT_STRIP_Y_FRAC_START = 0.0240f
    const val SUBJECT_STRIP_Y_FRAC_END   = 0.1080f

    // Subject cell read fracs within the full answer warp.
    const val SUBJECT_CELL_W_FRAC = 0.0104f
    const val SUBJECT_CELL_H_FRAC = 0.0270f

    // Fallback x-positions of the 3 subject bubbles (fracs of ANSWER_WARP_W):
    //   Mathematics (855 px), ESAS (1350 px), Professional EE (1845 px).
    val subjectFallbackXFracs = floatArrayOf(
        855f  / ANSWER_WARP_W,   // ≈ 0.356
        1350f / ANSWER_WARP_W,   // ≈ 0.563
        1845f / ANSWER_WARP_W    // ≈ 0.769
    )
    // Fallback y-position (frac of ANSWER_WARP_H).
    const val SUBJECT_FALLBACK_Y_FRAC = 0.0647f

    // ── Canonical SUBJECT sub-warp ────────────────────────────────────────────
    // The subject box, curve-rectified to a flat band so the 3 bubbles land on a fixed
    // locus regardless of bow (a fixed strip crop lets a bowed box cut off the bubbles).
    const val SUBJECT_WARP_W = 2400
    // Matches the box's natural aspect (≈2310×110) so rectify doesn't stretch it
    // vertically — a taller canvas balloons the thin band 2× and the bubbles with it.
    const val SUBJECT_WARP_H = 126
    // Loose prior centers for the 3 bubbles (MATH / ESAS / PROFESSIONAL EE), ×box width.
    // Exact x comes from detecting the bubble island, since bubble x shifts per capture on
    // non-flat paper; these only need to be roughly right with a generous window.
    val subjectBubbleXFracs = floatArrayOf(0.354f, 0.550f, 0.760f)
    // Bubble row center; constant ≈0.48 once the box is flattened.
    const val SUBJECT_SW_BUBBLE_Y_FRAC = 0.48f
    // CELL_W is narrow so the read window sits inside the bracket bars; a wider rect
    // catches the bars and makes an empty bracket read as filled as a shaded one.
    const val SUBJECT_SW_CELL_W_FRAC = 0.0125f
    const val SUBJECT_SW_CELL_H_FRAC = 0.34f

    // Per-column search regions on the answer warp. Pair(xFracRange, yFracRange): crop from
    // the full warp, resize to ANSWER_COL_WARP_W × _H, then feed AnswerColumnDetector.
    val answerColSearchRegions: Array<Pair<ClosedFloatingPointRange<Float>, ClosedFloatingPointRange<Float>>> = arrayOf(
        (0.0096f..0.1996f) to (0.0967f..1.0000f),  // col 1  Q 1–20
        (0.1996f..0.3942f) to (0.0967f..1.0000f),  // col 2  Q21–40
        (0.3942f..0.5892f) to (0.0967f..1.0000f),  // col 3  Q41–60
        (0.5892f..0.7846f) to (0.0967f..1.0000f),  // col 4  Q61–80
        (0.7846f..0.9829f) to (0.0967f..1.0000f)   // col 5  Q81–100
    )

    // Cell read fracs within ANSWER_COL_WARP_W × ANSWER_COL_WARP_H sub-warp.
    const val ANSWER_CELL_W_FRAC = 0.1137f
    const val ANSWER_CELL_H_FRAC = 0.0367f

    val answerChoiceXFracs = floatArrayOf(0.3112f, 0.4450f, 0.5788f, 0.7126f, 0.8465f)

    const val ANSWER_ROW_FIRST_Y_FRAC  = 0.0350f
    const val ANSWER_ROW_STRIDE_FRAC   = 0.0464f
    const val ANSWER_SUBGROUP_GAP_FRAC = 0.0274f

    // ── Convenience lookup ────────────────────────────────────────────────
    /** Returns the NormPoint for question [q] (1-based) and choice [choice] ('A'..'E'). */
    fun answerCell(q: Int, choice: Char): NormPoint {
        require(q in 1..100) { "Question must be 1..100" }
        require(choice in 'A'..'E') { "Choice must be A..E" }
        return answerGrid[q - 1][choice - 'A']
    }
}

/** A normalized (x, y) coordinate: fraction of warp canvas width/height. */
data class NormPoint(val x: Float, val y: Float)
