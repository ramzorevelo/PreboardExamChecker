package com.pbec.preboardexamchecker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pbec.preboardexamchecker.data.models.TransactionLog
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(log: TransactionLog): Long

    @Query("SELECT * FROM transaction_logs ORDER BY createdAt DESC")
    fun getAllTransactions(): Flow<List<TransactionLog>>
}
