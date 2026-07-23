package com.pbec.preboardexamchecker.data.models

/**
 * Binds one exam per subject into a single preboard administration ("Preboard Round 1").
 *
 * GWA and the institutional RESULTS sheet are computed per cluster: a student's three subject
 * scores must come from the *same* cluster, since several exams can exist per subject and the
 * scanned paper is not necessarily the latest. [examIdsBySubject] is a map (not three fixed
 * fields) so a future quiz mode / extra subjects stay representable.
 *
 * Firestore-only; clusters are a lightweight join record with no offline-scan dependency.
 */
data class ExamCluster(
    val id: Long = 0,
    val name: String = "",
    val examIdsBySubject: Map<String, Long> = emptyMap(),
    val schoolYear: String? = null,
    val createdAt: Long = 0,
)
