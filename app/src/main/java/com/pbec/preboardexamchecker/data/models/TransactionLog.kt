package com.pbec.preboardexamchecker.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transaction_logs")
data class TransactionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val action: String,
    val subject: String? = null,
    val details: String,
    val createdAt: Long = System.currentTimeMillis()
)
