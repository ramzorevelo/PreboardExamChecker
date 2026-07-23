package com.pbec.preboardexamchecker.domain.model

import com.pbec.preboardexamchecker.domain.usecase.SubjectGwaLine

/**
 * One student's aggregated result across the 3 subjects, ready for display and PDF export.
 *
 * Built by grouping that student's [com.pbec.preboardexamchecker.data.models.ScanResult]s by
 * (cluster, subject) — latest scan per subject within the cluster — and running
 * [com.pbec.preboardexamchecker.domain.usecase.CalculateGwaUseCase]. One row per student per cluster.
 */
data class StudentGwaRow(
    val studentId: String,
    val name: String,
    val block: String,
    val yearLevel: String,
    val program: String,
    val lines: List<SubjectGwaLine>,
    val gwa: Double,            // weighted average, 0..100
    val status: SummaryStatus,
    val lastScannedAt: Long,
    // Cluster (round) this GWA belongs to; null for pre-cluster scans.
    val clusterId: Long? = null,
    val clusterName: String? = null,
    val schoolYear: String? = null,
)

/** Stable identity for selection: a row is one (student, cluster), so both are needed to disambiguate. */
fun StudentGwaRow.selectionKey(): String = "$studentId#$clusterId"
