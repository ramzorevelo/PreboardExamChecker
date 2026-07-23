package com.pbec.preboardexamchecker.ui.scanner.scoring

/**
 * Central, developer-editable scoring configuration.
 *
 * Change the values here to adjust grading rules app-wide. (Later these may be surfaced in
 * app settings; for now they are the single source of truth in code.)
 *
 * REE model:
 *  - Per-subject rating  = (correct / total) * 100
 *  - Per-subject pass    = rating >= SUBJECT_PASS_FLOOR (50%) — the "no grade below 50%" floor
 *  - GWA                 = Σ(subject% * weight), weights below
 *  - Overall pass        = GWA >= GWA_PASSING_AVERAGE (70%) AND no subject below the floor
 */
object ScoringConfig {
    /** Minimum per-subject fraction (0..1) required to pass that subject. */
    const val SUBJECT_PASS_FLOOR = 0.50

    /** Minimum weighted average (0..1) required to pass overall. */
    const val GWA_PASSING_AVERAGE = 0.70

    /**
     * Subject weights for the General Weighted Average. Keys must match the subject strings used
     * across the app (see SessionSetupScreen / Question.subject). Weights should sum to 1.0.
     */
    val SUBJECT_WEIGHTS: Map<String, Double> = mapOf(
        "Mathematics" to 0.25,
        "ESAS" to 0.30,
        "Professional EE" to 0.45,
    )
}
