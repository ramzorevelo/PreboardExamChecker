package com.pbec.preboardexamchecker.ui.scanner.processor

import com.pbec.preboardexamchecker.data.models.Student

/**
 * setAQuestionIds/setBQuestionIds are ordered to match the printed PDF: physical answer
 * position i maps to setXQuestionIds[i]. studentMap keys are 6-digit ID strings.
 */
data class ScanContext(
    val examId: Long,
    val examName: String,
    val subject: String,
    val setAQuestionIds: List<Long>,
    val setBQuestionIds: List<Long>,
    val setAKeyMap: Map<Long, String?>,
    val setBKeyMap: Map<Long, String?>,
    val studentMap: Map<String, Student>,
    // Non-null when this context belongs to a cluster session; tags results for per-cluster GWA.
    val clusterId: Long? = null,
    val clusterName: String? = null,
)
