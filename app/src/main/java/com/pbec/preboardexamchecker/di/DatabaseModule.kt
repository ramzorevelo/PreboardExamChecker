package com.pbec.preboardexamchecker.di

import android.content.Context
import com.google.gson.Gson
import com.pbec.preboardexamchecker.data.AppDatabase
import com.pbec.preboardexamchecker.data.dao.ExamDao
import com.pbec.preboardexamchecker.data.dao.QuestionDao
import com.pbec.preboardexamchecker.data.dao.ScanResultDao
import com.pbec.preboardexamchecker.data.dao.TransactionLogDao
import com.pbec.preboardexamchecker.data.models.ListLongConverter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideGson(): Gson {
        return Gson()
    }

    @Provides
    @Singleton
    fun provideListLongConverter(gson: Gson): ListLongConverter {
        return ListLongConverter(gson)
    }


    @Provides
    @Singleton
    fun provideApplicationContext(@ApplicationContext context: Context): Context {
        return context
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context, gson: Gson): AppDatabase {
        return AppDatabase.getDatabase(context, gson)
    }

    @Provides
    fun provideQuestionDao(appDatabase: AppDatabase): QuestionDao {
        return appDatabase.questionDao()
    }

    @Provides
    fun provideExamDao(appDatabase: AppDatabase): ExamDao {
        return appDatabase.examDao()
    }

    @Provides
    fun provideTransactionLogDao(appDatabase: AppDatabase): TransactionLogDao {
        return appDatabase.transactionLogDao()
    }

    @Provides
    fun provideScanResultDao(appDatabase: AppDatabase): ScanResultDao {
        return appDatabase.scanResultDao()
    }
}