package com.pbec.preboardexamchecker.ui.scanner.scoring

interface ScoringStrategy {
    fun isPassed(score: Int, total: Int): Boolean
    fun percentage(score: Int, total: Int): Double
    val displayThresholdLabel: String
}
