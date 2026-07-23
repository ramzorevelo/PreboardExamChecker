package com.pbec.preboardexamchecker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.pbec.preboardexamchecker.data.dao.ExamDao
import com.pbec.preboardexamchecker.data.dao.QuestionDao
import com.pbec.preboardexamchecker.data.dao.ScanResultDao
import com.pbec.preboardexamchecker.data.dao.TransactionLogDao
import com.pbec.preboardexamchecker.data.models.Exam
import com.pbec.preboardexamchecker.data.models.ListLongConverter
import com.pbec.preboardexamchecker.data.models.Question
import com.pbec.preboardexamchecker.data.models.ScanResult
import com.pbec.preboardexamchecker.data.models.TransactionLog

@Database(entities = [Question::class, Exam::class, TransactionLog::class, ScanResult::class], version = 11, exportSchema = true)
@TypeConverters(ListLongConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun questionDao(): QuestionDao
    abstract fun examDao(): ExamDao
    abstract fun transactionLogDao(): TransactionLogDao
    abstract fun scanResultDao(): ScanResultDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v1→v11 chain, derived from the exported schemas in app/schemas. No destructive
        // fallback: scan_results is the only copy of graded exams, so a bad migration must throw.

        /** Schema unchanged. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { /* no-op */ }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN topic TEXT")
                db.execSQL("ALTER TABLE questions ADD COLUMN customSessionName TEXT")
            }
        }

        /** Schema unchanged. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) { /* no-op */ }
        }

        /** createSql verbatim from schemas/5.json. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `transaction_logs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`action` TEXT NOT NULL, `subject` TEXT, `details` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
            }
        }

        /** Schema unchanged. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) { /* no-op */ }
        }

        /** DEFAULT '' backfills old rows; Room skips default validation since the entity declares none. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN questionBankId TEXT NOT NULL DEFAULT ''")
            }
        }

        /** createSql verbatim from schemas/8.json. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `scan_results` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`studentId` TEXT NOT NULL, `studentName` TEXT NOT NULL, " +
                        "`studentBlock` TEXT NOT NULL, `studentYearLevel` TEXT NOT NULL, " +
                        "`studentProgram` TEXT NOT NULL, `subject` TEXT NOT NULL, " +
                        "`examId` INTEGER NOT NULL, `examName` TEXT NOT NULL, `testSet` TEXT NOT NULL, " +
                        "`rawAnswers` TEXT NOT NULL, `score` INTEGER NOT NULL, `total` INTEGER NOT NULL, " +
                        "`passed` INTEGER NOT NULL, `scannedAt` INTEGER NOT NULL, " +
                        "`syncedAt` INTEGER, `remoteId` TEXT)"
                )
            }
        }

        /** v8 → v9: add the nullable `deletedAt` column to scan_results (Trash / soft-delete).
         *  An additive migration preserves existing scan records instead of dropping them. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scan_results ADD COLUMN deletedAt INTEGER")
            }
        }

        /** v9 → v10: add `trashedByExamId` so records deleted together with their exam are
         *  restored/purged as a unit. Additive — preserves existing records. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scan_results ADD COLUMN trashedByExamId INTEGER")
            }
        }

        /** v10 → v11: tag scan_results with the cluster (round) they were scored under, so GWA and
         *  the RESULTS sheet group a student's three subjects by cluster. Additive; old rows stay
         *  null (pre-cluster). */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scan_results ADD COLUMN clusterId INTEGER")
                db.execSQL("ALTER TABLE scan_results ADD COLUMN clusterName TEXT")
            }
        }

        fun getDatabase(context: Context, gson: Gson): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pre_board_exam_checker_db"
                )
                    .addTypeConverter(ListLongConverter(gson))
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                        MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
