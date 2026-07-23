package com.pbec.preboardexamchecker.domain.model

data class SummaryStats(
    val totalScanned: Int = 0,
    val passCount: Int = 0,
    val failCount: Int = 0,
    val passRate: Double = 0.0,
    val averageScore: Double = 0.0,
    val averagePercentage: Double = 0.0,
)
