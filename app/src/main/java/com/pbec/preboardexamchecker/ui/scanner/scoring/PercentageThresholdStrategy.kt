package com.pbec.preboardexamchecker.ui.scanner.scoring

class PercentageThresholdStrategy : ScoringStrategy {

    override val displayThresholdLabel = "${(PASS_THRESHOLD * 100).toInt()}%"

    override fun isPassed(score: Int, total: Int): Boolean {
        if (total == 0) return false
        return score.toDouble() / total >= PASS_THRESHOLD
    }

    override fun percentage(score: Int, total: Int): Double {
        if (total == 0) return 0.0
        return score.toDouble() / total * 100.0
    }

    companion object {
        /** Per-subject pass floor. Edit in [ScoringConfig]. */
        const val PASS_THRESHOLD = ScoringConfig.SUBJECT_PASS_FLOOR
    }
}
