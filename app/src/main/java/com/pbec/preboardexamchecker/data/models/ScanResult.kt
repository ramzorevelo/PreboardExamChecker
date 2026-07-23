package com.pbec.preboardexamchecker.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_results")
data class ScanResult(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val studentId: String,
    val studentName: String,
    val studentBlock: String,
    val studentYearLevel: String,
    val studentProgram: String,
    val subject: String,
    val examId: Long,
    val examName: String,
    /** Cluster this paper was scored under; null for pre-cluster / single-exam scans. Groups the
     *  student's three subjects for GWA and the RESULTS sheet. */
    val clusterId: Long? = null,
    val clusterName: String? = null,
    val testSet: String,
    val rawAnswers: String,
    val score: Int,
    val total: Int,
    val passed: Boolean,
    val scannedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,
    val remoteId: String? = null,
    /** null = active; timestamp = soft-deleted, purgeable once older than the 30-day retention window. */
    val deletedAt: Long? = null,
    /** Set when trashed together with its exam; restored/purged with that exam, not as an individual paper. */
    val trashedByExamId: Long? = null,
)
