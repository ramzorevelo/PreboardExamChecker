package com.pbec.preboardexamchecker.domain.usecase

import com.pbec.preboardexamchecker.data.models.ScanResult
import com.pbec.preboardexamchecker.domain.model.SummaryStats
import javax.inject.Inject

class GetSummaryStatsUseCase @Inject constructor() {
    operator fun invoke(results: List<ScanResult>): SummaryStats {
        if (results.isEmpty()) return SummaryStats()
        val passCount = results.count { it.passed }
        val failCount = results.size - passCount
        val passRate = passCount.toDouble() / results.size * 100.0
        val averageScore = results.map { it.score }.average()
        val averagePercentage = results.map {
            if (it.total > 0) it.score.toDouble() / it.total * 100.0 else 0.0
        }.average()
        return SummaryStats(
            totalScanned = results.size,
            passCount = passCount,
            failCount = failCount,
            passRate = passRate,
            averageScore = averageScore,
            averagePercentage = averagePercentage,
        )
    }
}
